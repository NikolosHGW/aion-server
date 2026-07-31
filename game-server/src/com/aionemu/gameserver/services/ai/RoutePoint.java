package com.aionemu.gameserver.services.ai;

public record RoutePoint(float x, float y, float z) {

	public RoutePoint {
		if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
			throw new IllegalArgumentException("Route point coordinates must be finite");
	}
}
