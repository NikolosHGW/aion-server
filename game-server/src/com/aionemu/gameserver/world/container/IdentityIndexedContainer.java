package com.aionemu.gameserver.world.container;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class IdentityIndexedContainer<I, N, V> {

	private final ConcurrentHashMap<I, V> valuesById = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<N, V> valuesByName = new ConcurrentHashMap<>();

	public synchronized V add(I id, N name, V value) {
		V valueWithId = valuesById.putIfAbsent(id, value);
		if (valueWithId != null)
			return valueWithId;

		V valueWithName = valuesByName.putIfAbsent(name, value);
		if (valueWithName != null) {
			valuesById.computeIfPresent(id, (_, current) -> current == value ? null : current);
			return valueWithName;
		}
		return null;
	}

	public synchronized void remove(I id, N name, V value) {
		valuesById.computeIfPresent(id, (_, current) -> current == value ? null : current);
		valuesByName.computeIfPresent(name, (_, current) -> current == value ? null : current);
	}

	public synchronized void updateName(N oldName, N newName, V value) {
		if (Objects.equals(oldName, newName)) {
			valuesByName.put(newName, value);
			return;
		}
		valuesByName.put(newName, value);
		valuesByName.computeIfPresent(oldName, (_, current) -> current == value ? null : current);
	}

	public V getById(I id) {
		return valuesById.get(id);
	}

	public V getByName(N name) {
		return valuesByName.get(name);
	}

	public Collection<V> values() {
		return valuesById.values();
	}
}
