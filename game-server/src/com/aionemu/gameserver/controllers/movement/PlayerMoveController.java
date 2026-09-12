package com.aionemu.gameserver.controllers.movement;

import com.aionemu.gameserver.configs.main.FallDamageConfig;
import com.aionemu.gameserver.model.EmotionType;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.network.aion.serverpackets.SM_ATTACK_STATUS.LOG;
import com.aionemu.gameserver.network.aion.serverpackets.SM_ATTACK_STATUS.TYPE;
import com.aionemu.gameserver.network.aion.serverpackets.SM_EMOTION;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MOVE;
import com.aionemu.gameserver.services.player.PlayerReviveService;
import com.aionemu.gameserver.skillengine.model.Skill;
import com.aionemu.gameserver.taskmanager.tasks.PlayerMoveTaskManager;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.utils.stats.StatFunctions;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.WorldPosition;

/**
 * @author ATracer
 */
public class PlayerMoveController extends PlayableMoveController<Player> {

	private float fallDistance;
	private float lastFallZ;
	private byte lastMovementMask;
	private long lastPositionFromClientMillis;
	private WorldPosition lastPositionFromClient;
	private long lastRandomMoveLocEffectTimeMillis;
	private volatile float serverControlledMovementSpeed = Float.NaN;

	public PlayerMoveController(Player owner) {
		super(owner);
	}

	/**
	 * Starts movement calculated by the server without emulating a client movement packet.
	 */
	public boolean startServerControlledMove(float x, float y, float z, byte heading) {
		if (!owner.isSpawned() || !owner.canPerformMove())
			return false;

		setNewDirection(x, y, z, heading);
		World.getInstance().updatePosition(owner, owner.getX(), owner.getY(), owner.getZ(), heading, false);
		updateLastMove();
		started.set(true);
		setInMove(true);
		movementMask = MovementMask.NPC_STARTMOVE;
		if (!PlayerMoveTaskManager.getInstance().addPlayer(owner)) {
			started.set(false);
			setInMove(false);
			movementMask = MovementMask.IMMEDIATE;
			return false;
		}
		PacketSendUtility.broadcastToSightedPlayers(owner, new SM_MOVE(owner));
		return true;
	}

	/**
	 * Applies only to movement calculated by this controller. It deliberately does not alter PlayerGameStats.
	 */
	public boolean setServerControlledMovementSpeed(float speed) {
		if (!isServerControlledMovementSpeedAllowed(owner.getClientConnection() != null, speed))
			return false;
		if (Float.compare(serverControlledMovementSpeed, speed) == 0)
			return true;
		serverControlledMovementSpeed = speed;
		PacketSendUtility.broadcastToSightedPlayers(owner, new SM_EMOTION(owner, EmotionType.CHANGE_SPEED, speed), true);
		return true;
	}

	static boolean isServerControlledMovementSpeedAllowed(boolean clientConnected, float speed) {
		return !clientConnected && Float.isFinite(speed) && speed > 0;
	}

	public void clearServerControlledMovementSpeed() {
		if (!Float.isFinite(serverControlledMovementSpeed))
			return;
		serverControlledMovementSpeed = Float.NaN;
		PacketSendUtility.broadcastToSightedPlayers(owner,
			new SM_EMOTION(owner, EmotionType.CHANGE_SPEED, owner.getGameStats().getMovementSpeedFloat()), true);
	}

	public boolean hasServerControlledMovementSpeed() {
		return Float.isFinite(serverControlledMovementSpeed);
	}

	@Override
	protected float getEffectiveMovementSpeed() {
		return owner.getClientConnection() == null && Float.isFinite(serverControlledMovementSpeed)
			? serverControlledMovementSpeed
			: super.getEffectiveMovementSpeed();
	}

	public void stopServerControlledMove() {
		abortMove();
	}

	@Override
	public void abortMove() {
		super.abortMove();
		clearServerControlledMovementSpeed();
		stopFalling(owner.getZ());
	}

	public byte getLastMovementMask() {
		return lastMovementMask;
	}

	public long getLastPositionFromClientMillis() {
		return lastPositionFromClientMillis;
	}

	public WorldPosition getLastPositionFromClient() {
		return lastPositionFromClient;
	}

	public void resetLastPositionFromClient() {
		lastPositionFromClient = null;
	}

	/**
	 * This method should only be called from player move packets, not any calculated intermediate position updates by the server
	 */
	public void onMoveFromClient() {
		updateLastMove();
		lastMovementMask = getMovementMask();
		lastPositionFromClientMillis = System.currentTimeMillis();
		if (lastPositionFromClient == null || lastPositionFromClient.getMapId() != owner.getWorldId())
			lastPositionFromClient = new WorldPosition(owner.getWorldId(), owner.getX(), owner.getY(), owner.getZ(), owner.getHeading());
		else
			lastPositionFromClient.setXYZH(owner.getX(), owner.getY(), owner.getZ(), owner.getHeading());
	}

	public void resetToLastPositionFromClient() {
		abortMove();
		if (lastPositionFromClient != null && owner.getWorldId() == lastPositionFromClient.getMapId())
			owner.getPosition().setXYZH(lastPositionFromClient.getX(), lastPositionFromClient.getY(), lastPositionFromClient.getZ(),
					lastPositionFromClient.getHeading());
	}

	public void updateFalling(float newZ) {
		if (lastFallZ != 0) {
			fallDistance += lastFallZ - newZ;
			if (fallDistance >= FallDamageConfig.MAXIMUM_DISTANCE_MIDAIR && owner.getController().die(TYPE.FALL_DAMAGE, LOG.REGULAR, owner)) {
				PlayerReviveService.scheduleReviveAtBase(owner, 1000, 0);
				return;
			}
		}
		lastFallZ = newZ;
		owner.getObserveController().notifyMoveObservers();
	}

	public void stopFalling(float newZ) {
		if (lastFallZ == 0)
			return;

		if (!owner.isFlying() && !owner.isDead()) {
			fallDistance += lastFallZ - newZ;
			int damage = StatFunctions.calculateFallDamage(owner, fallDistance);
			if (damage > 0) {
				owner.getLifeStats().reduceHp(TYPE.FALL_DAMAGE, damage, 0, LOG.REGULAR, owner);
				owner.getObserveController().notifyAttackedObservers(owner, 0);
			}
		}
		fallDistance = 0;
		lastFallZ = 0;
		owner.getObserveController().notifyMoveObservers();
	}

	public void setHasMovedByRandomMoveLocEffect(Skill skill) {
		// delayMillis is required because instant skills (like Power: Emergency Teleport I) are not scheduled with hitTime in endCast
		int delayMillis = skill.isInstantSkill() ? skill.getHitTime() : 0;
		this.lastRandomMoveLocEffectTimeMillis = System.currentTimeMillis() + delayMillis;
	}

	public boolean hasMovedByRandomMoveLocEffect() {
		return lastRandomMoveLocEffectTimeMillis != 0 && System.currentTimeMillis() - lastRandomMoveLocEffectTimeMillis < 300;
	}
}
