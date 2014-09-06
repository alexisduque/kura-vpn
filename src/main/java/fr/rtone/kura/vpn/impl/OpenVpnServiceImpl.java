package fr.rtone.kura.vpn.impl;

import org.eclipse.kura.KuraErrorCode;
import org.eclipse.kura.KuraException;
import fr.rtone.kura.vpn.VpnService;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenVpnServiceImpl implements VpnService {
	private static final Logger logger = LoggerFactory
			.getLogger(OpenVpnServiceImpl.class);
	private static final String OPENVPN_MANAGE_IP = "127.0.0.1";
	private static final int OPENVPN_MANAGE_PORT = 50002;
	private static String openVpnExePath;
	private static File openVpnLogFile;
	private static File openVpnConfigFile;
	private static final String LINE_SEPARATOR = System
			.getProperty("line.separator");
	private String ipAddress;
	private Process process;
	private BufferedReader stderrBufferedReader;
	private BufferedReader stdoutBufferedReader;
	private Thread stderrStreamGobbler;
	private Thread stdoutStreamGobbler;
	private BufferedWriter openVpnLogBufferedWriter;
	private Socket manageSocket;
	private BufferedReader manageSocketBufferedReader;
	private BufferedWriter manageSocketBufferedWriter;

	static {
		openVpnExePath = "openvpn";
		openVpnLogFile = new File("/var/log/vpn_rtone.log");

		String path = System.getProperty("java.io.tmpdir");
		openVpnConfigFile = new File(path + File.separator + "vpn_rtone.conf");
	}

	public void start(String username, String password) throws KuraException {
		if (isRunning()) {
			throw new KuraException(KuraErrorCode.INTERNAL_ERROR,
					new Object[] { "Already running" });
		}
		if ((username == null) || (username.isEmpty()) || (password == null)
				|| (password.isEmpty())) {
			throw new KuraException(KuraErrorCode.INTERNAL_ERROR,
					new Object[] { "Null or empty username/password" });
		}
		if (openVpnConfigFile == null) {
			throw new KuraException(KuraErrorCode.INTERNAL_ERROR,
					new Object[] { "null OpenVPN configuration" });
		}
		String configPath = null;
		try {
			configPath = openVpnConfigFile.getCanonicalPath();
		} catch (IOException e) {
			throw new KuraException(
					KuraErrorCode.INTERNAL_ERROR,
					e,
					new Object[] { "Failed to get path of OpenVPN Rtone configuration file" });
		}
		if (!openVpnLogFile.exists()) {
			try {
				openVpnLogFile.createNewFile();
			} catch (IOException e) {
				throw new KuraException(
						KuraErrorCode.INTERNAL_ERROR,
						e,
						new Object[] { "Cannot create OpenVPN Rtone log file - Maybe your are not root ?" });
			}
		}
		try {
			openVpnLogBufferedWriter = getOpenVpnBufferedWriter(openVpnLogFile);
		} catch (FileNotFoundException e) {
			throw new KuraException(KuraErrorCode.INTERNAL_ERROR, e,
					new Object[0]);
		}
		String[] cmd = { openVpnExePath, "--config", configPath,
				"--management", OPENVPN_MANAGE_IP,
				Integer.valueOf(OPENVPN_MANAGE_PORT).toString(),
				"--management-query-passwords", "--management-hold" };
		logger.debug("VPN start command: " + cmd.toString());
		try {
			try {
				process = Runtime.getRuntime().exec(cmd);
			} catch (IOException e) {
				throw new KuraException(KuraErrorCode.INTERNAL_ERROR, e,
						new Object[] { "Failed to start OpenVPN command" });
			}
			stderrBufferedReader = getProcessStderrBufferedReader(process);
			stdoutBufferedReader = getProcessStdoutBufferedReader(process);

			stderrStreamGobbler = new Thread(new Runnable() {
				public void run() {
					try {
						String line = null;
						while ((line = stderrBufferedReader.readLine()) != null) {
							try {
								openVpnLogBufferedWriter.write(line
										+ OpenVpnServiceImpl.LINE_SEPARATOR);
								openVpnLogBufferedWriter.flush();
							} catch (IOException e) {
								OpenVpnServiceImpl.logger.error(
										"Failed to write OpenVPN log file", e);
							}
						}
						OpenVpnServiceImpl.logger
								.info("End of OpenVPN stderr stream reached");
					} catch (IOException e) {
						OpenVpnServiceImpl.logger.error(
								"Exception reading process OpenVPN stderr", e);
					}
				}
			});
			stderrStreamGobbler.start();

			stdoutStreamGobbler = new Thread(new Runnable() {
				public void run() {
					try {
						String line = null;
						while ((line = stdoutBufferedReader.readLine()) != null) {
							try {
								openVpnLogBufferedWriter.write(line
										+ OpenVpnServiceImpl.LINE_SEPARATOR);
								openVpnLogBufferedWriter.flush();
							} catch (IOException e) {
								OpenVpnServiceImpl.logger.error(
										"Failed to write OpenVPN log file", e);
							}
						}
						OpenVpnServiceImpl.logger
								.debug("End of OpenVPN stdout stream reached");
					} catch (IOException e) {
						OpenVpnServiceImpl.logger.info(
								"Exception reading OpenVPN process stdout", e);
					}
				}
			});
			stdoutStreamGobbler.start();
			logger.debug("Waiting for OpenVPN start completely");
			try {
				Thread.sleep(200L);
			} catch (InterruptedException e) {
				throw new KuraException(
						KuraErrorCode.INTERNAL_ERROR,
						new Object[] {
								"Interrupted while waiting to reconnect to manage socket",
								e });
			}
			logger.debug("Connecting to OpenVPN Management service...");
			// for (int tries = 0; tries < 10; tries++) {
			try {
				manageSocket = new Socket(OPENVPN_MANAGE_IP,
						OPENVPN_MANAGE_PORT);
				OpenVpnServiceImpl.logger.info(OPENVPN_MANAGE_IP + " sock opened");
			} catch (UnknownHostException e) {
				logger.warn("Cannot connect to manage socket: "
						+ OPENVPN_MANAGE_IP + ":" + OPENVPN_MANAGE_PORT, e);
			} catch (IOException e) {
				logger.warn("Cannot connect to manage socket: "
						+ OPENVPN_MANAGE_IP + ":" + OPENVPN_MANAGE_PORT, e);
			}
			try {
				Thread.sleep(100L);
			} catch (InterruptedException e) {
				throw new KuraException(
						KuraErrorCode.INTERNAL_ERROR,
						new Object[] {
								"Interrupted while waiting to reconnect to manage socket",
								e });
			}
			// }
			if (!manageSocket.isConnected()) {
				throw new KuraException(
						KuraErrorCode.INTERNAL_ERROR,
						new Object[] { "Cannot connect to manage socket: "
								+ OPENVPN_MANAGE_IP + ":" + OPENVPN_MANAGE_PORT });
			}
			logger.info("Connected to OpenVPN manage socket: "
					+ OPENVPN_MANAGE_IP + ":" + OPENVPN_MANAGE_PORT);
			try {
				manageSocketBufferedReader = getSocketBufferedReader(manageSocket);
			} catch (IOException e) {
				throw new KuraException(
						KuraErrorCode.INTERNAL_ERROR,
						e,
						new Object[] { "Cannot get inputStream for OpenVPN manage socket" });
			}
			try {
				manageSocketBufferedWriter = getSocketBufferedWriter(manageSocket);
			} catch (IOException e) {
				throw new KuraException(
						KuraErrorCode.INTERNAL_ERROR,
						e,
						new Object[] { "Cannot get output stream for OpenVPN manage socket" });
			}
			try {
				sendCommand(manageSocketBufferedWriter, "state on");

				expect(manageSocketBufferedReader,
						new String[] { ">HOLD:Waiting for hold release" },
						null, 5000L);

				sendCommand(manageSocketBufferedWriter, "hold release");

				expect(manageSocketBufferedReader,
						new String[] { ">PASSWORD:Need 'Auth' username/password" },
						null, 5000L);

				sendCommand(manageSocketBufferedWriter, "username \"Auth\" "
						+ username);

				sendCommand(manageSocketBufferedWriter, "password \"Auth\" "
						+ password);

				String line = expect(
						manageSocketBufferedReader,
						new String[] { "CONNECTED,SUCCESS" },
						new String[] { ">PASSWORD:Verification Failed: 'Auth'" },
						30000L);
				if (line.contains("CONNECTED,SUCCESS")) {
					ipAddress = findNetInterfaceIpddress(line);
				} else {
					throw new KuraException(KuraErrorCode.INTERNAL_ERROR,
							new Object[] { "Connect failed: " + line });
				}
			} catch (IOException localIOException1) {
				throw new KuraException(
						KuraErrorCode.INTERNAL_ERROR,
						new Object[] { "Exception using OpenVPN manage socket" });
			} catch (InterruptedException localInterruptedException1) {
				throw new KuraException(
						KuraErrorCode.INTERNAL_ERROR,
						new Object[] { "Exception OpenVPN using manage socket" });
			} catch (KuraException e) {
				throw e;
			}
			closeManageSession();
		} catch (KuraException e) {
			cleanup();
			throw e;
		}
	}

	public void stop() throws KuraException {
		cleanup();
	}

	public void setConfiguration(byte[] vpnConfiguration) throws KuraException {
		logger.debug("Writting OpenVPN configuration file");
		if (openVpnConfigFile == null) {
			throw new KuraException(KuraErrorCode.INTERNAL_ERROR,
					new Object[] { "OpenVPN configuration file not defined" });
		}
		byte[] content = vpnConfiguration;
		try {
			FileUtils.writeByteArrayToFile(openVpnConfigFile, content);
		} catch (IOException e) {
			throw new KuraException(KuraErrorCode.INTERNAL_ERROR, new Object[] {
					"Cannot write OpenVPN configuration file", e });
		}
	}

	public byte[] getConfiguration() throws KuraException {
		logger.debug("Get OpenVPN configuration from file");
		byte[] content = null;
		if (openVpnConfigFile != null) {
			try {
				content = FileUtils.readFileToByteArray(openVpnConfigFile);
			} catch (IOException e) {
				logger.error("Failed to read configuration file", e);
				throw new KuraException(KuraErrorCode.INTERNAL_ERROR, e,
						new Object[] { "Failed to read configuration file" });
			}
		}
		return content;
	}

	public boolean isRunning() {
		boolean running = false;
		if (process != null) {
			try {
				process.exitValue();
			} catch (IllegalThreadStateException localIllegalThreadStateException) {
				running = true;
			}
		}
		return running;
	}

	public String getNetInterfaceIpAddress() {
		if (isRunning()) {
			logger.info("VPN Ip address : " + ipAddress);
			return ipAddress;
		}
		return null;
	}

	private String findNetInterfaceIpddress(String line) {
		String s = null;
		try {
			int index = line.lastIndexOf("CONNECTED,SUCCESS");
			if (index != -1) {
				s = line.substring(index);
				String[] parts = s.split(",");
				return parts[2];
			}
		} catch (IndexOutOfBoundsException localIndexOutOfBoundsException) {
			logger.error("Cannot parse VPN Ip address from OpenVPN stdout", s);
			logger.error("Expected 4 comma separated tokens in: {}", s);
		}
		return null;
	}

	private static void sendCommand(BufferedWriter br, String command)
			throws IOException {
		logger.debug(command);
		br.write(command + "\n");
		br.flush();
	}

	private String expect(BufferedReader br, String[] stdoutExpected,
			String[] stderrExpected, long millis) throws IOException,
			InterruptedException, KuraException {
		StringBuilder sb = new StringBuilder();

		long remaining = millis;
		while (remaining > 0L) {
			while (br.ready()) {
				int c = br.read();
				if (c == -1) {
					throw new KuraException(
							KuraErrorCode.INTERNAL_ERROR,
							new Object[] { "OpenVPN Manage socket stream closed" });
				}
				if ((c == 13) || (c == 10)) {
					if (sb.length() != 0) {
						String line = sb.toString();
						logger.debug("Received : " + line);
						String[] arrayOfString;
						int j;
						int i;
						if (stdoutExpected != null) {
							j = (arrayOfString = stdoutExpected).length;
							for (i = 0; i < j; i++) {
								String expected = arrayOfString[i];
								if (line.contains(expected)) {
									return line;
								}
							}
						}
						if (stderrExpected != null) {
							j = (arrayOfString = stderrExpected).length;
							for (i = 0; i < j; i++) {
								String expected = arrayOfString[i];
								if (line.contains(expected)) {
									return line;
								}
							}
						}
						sb = new StringBuilder();
					}
				} else {
					sb.append((char) c);
				}
			}
			Thread.sleep(100L);
			remaining -= 100L;
		}
		throw new KuraException(
				KuraErrorCode.INTERNAL_ERROR,
				new Object[] { "Timeout expired expecting from OpenVPN stdout: "
						+ Arrays.toString(stdoutExpected)
						+ " and stderr: "
						+ Arrays.toString(stderrExpected) });
	}

	private static BufferedReader getProcessStderrBufferedReader(Process process) {
		InputStream is = process.getErrorStream();
		InputStreamReader isr = new InputStreamReader(is);
		return new BufferedReader(isr);
	}

	private static BufferedReader getProcessStdoutBufferedReader(Process process) {
		InputStream is = process.getInputStream();
		InputStreamReader isr = new InputStreamReader(is);
		return new BufferedReader(isr);
	}

	private static BufferedReader getSocketBufferedReader(Socket socket)
			throws IOException {
		InputStream is = socket.getInputStream();
		InputStreamReader isr = new InputStreamReader(is);
		return new BufferedReader(isr);
	}

	private static BufferedWriter getSocketBufferedWriter(Socket socket)
			throws IOException {
		OutputStream os = socket.getOutputStream();
		OutputStreamWriter osw = new OutputStreamWriter(os);
		return new BufferedWriter(osw);
	}

	private static BufferedWriter getOpenVpnBufferedWriter(File file)
			throws FileNotFoundException {
		OutputStream os = new FileOutputStream(file);
		OutputStreamWriter osw = new OutputStreamWriter(os);
		return new BufferedWriter(osw);
	}

	private void cleanup() {
		closeManageSession();
		if (process != null) {
			process.destroy();
			process = null;
		}
		if (stderrStreamGobbler != null) {
			stderrStreamGobbler.interrupt();
			stderrStreamGobbler = null;
		}
		if (stdoutStreamGobbler != null) {
			stdoutStreamGobbler.interrupt();
			stdoutStreamGobbler = null;
		}
		stderrBufferedReader = null;
		stdoutBufferedReader = null;
		if (openVpnLogBufferedWriter != null) {
			try {
				openVpnLogBufferedWriter.close();
			} catch (IOException e) {
				logger.warn("Exception performing cleanup", e);
			}
			openVpnLogBufferedWriter = null;
		}
		ipAddress = null;
	}

	private void closeManageSession() {
		logger.debug("Closing OpenVPN management session");
		if (manageSocketBufferedWriter != null) {
			try {
				sendCommand(manageSocketBufferedWriter, "exit");
			} catch (IOException e) {
				logger.warn("Exception closing manage session", e);
			}
		}
		if (manageSocket != null) {
			try {
				manageSocket.close();
			} catch (IOException e) {
				logger.warn("Exception closing manage session", e);
			}
			manageSocket = null;
		}
		manageSocketBufferedReader = null;
		manageSocketBufferedWriter = null;
	}
}