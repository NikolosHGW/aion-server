package com.aionemu.gameserver.services.ai;

/** Destination refreshes do not hide an actor that never actually moves. Clock and coordinates are supplied by the controller. */
final class MovementProgressWatchdog {
	private long lastProgress = -1;
	private double x, y, z;
	private int stalls;

	boolean stalled(long now, double nx, double ny, double nz, boolean movementExpected) {
		if (!movementExpected || lastProgress < 0) { anchor(now, nx, ny, nz); return false; }
		double distanceSquared = (nx - x) * (nx - x) + (ny - y) * (ny - y) + (nz - z) * (nz - z);
		if (Double.isFinite(distanceSquared) && distanceSquared >= 0.0625) {
			stalls = 0; anchor(now, nx, ny, nz); return false;
		}
		if (now - lastProgress < 8_000) return false;
		stalls++; anchor(now, nx, ny, nz); return true;
	}
	boolean exhausted() { return stalls >= 3; }
	int stalls() { return stalls; }
	void reset() { lastProgress = -1; stalls = 0; }
	private void anchor(long now, double nx, double ny, double nz) { lastProgress = now; x = nx; y = ny; z = nz; }
}
