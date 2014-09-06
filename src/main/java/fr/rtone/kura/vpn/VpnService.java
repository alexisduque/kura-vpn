package fr.rtone.kura.vpn;

import org.eclipse.kura.KuraException;

public abstract interface VpnService {
	public abstract void start(String cloudUsername, String cloudPassword)
			throws KuraException;

	public abstract void stop() throws KuraException;

	public abstract void setConfiguration(byte[] vpnConfig)
			throws KuraException;

	public abstract byte[] getConfiguration() throws KuraException;

	public abstract boolean isRunning();

	public abstract String getNetInterfaceIpAddress();
}