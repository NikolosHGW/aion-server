package com.aionemu.gameserver.services.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.AIConfig;
import com.aionemu.gameserver.configs.main.GeoDataConfig;
import com.aionemu.gameserver.geoEngine.collision.IgnoreProperties;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.TaskId;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.model.account.PlayerAccountData;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.AccountService;
import com.aionemu.gameserver.services.ai.combat.CombatActionGateway;
import com.aionemu.gameserver.services.ai.combat.CombatContributionOwnerResolver;
import com.aionemu.gameserver.services.ai.combat.CombatNpcAllowlist;
import com.aionemu.gameserver.services.ai.combat.CompanionCombatController;
import com.aionemu.gameserver.services.ai.combat.DefaultCombatActionGateway;
import com.aionemu.gameserver.services.ai.combat.OwnerAttackSignalSource;
import com.aionemu.gameserver.services.ai.combat.QuestCombatAuthorizationPolicy;
import com.aionemu.gameserver.services.ai.quest.CompanionQuestExecutionRuntime;
import com.aionemu.gameserver.services.ai.quest.CompanionGoalSession;
import com.aionemu.gameserver.services.ai.quest.CompanionGoalSession.ChoiceResult;
import com.aionemu.gameserver.services.ai.quest.CompanionGoalSession.ChoiceStatus;
import com.aionemu.gameserver.services.ai.quest.QuestGoalCandidateProvider;
import com.aionemu.gameserver.services.ai.quest.QuestGoalPlan;
import com.aionemu.gameserver.services.ai.quest.QuestGoalProposalResult;
import com.aionemu.gameserver.services.ai.quest.QuestGoalPlanner;
import com.aionemu.gameserver.services.ai.quest.QuestGoalPlanner.CandidateAssessment;
import com.aionemu.gameserver.services.ai.quest.QuestGoalReason;
import com.aionemu.gameserver.services.ai.quest.QuestExecutionAllowlist;
import com.aionemu.gameserver.services.ai.quest.QuestExecutionCommandFormatter;
import com.aionemu.gameserver.services.ai.quest.QuestExecutionPlan;
import com.aionemu.gameserver.services.ai.quest.QuestExecutionPlanProvider;
import com.aionemu.gameserver.services.ai.quest.QuestExecutionRole;
import com.aionemu.gameserver.services.ai.quest.QuestExecutionRuntimeContext;
import com.aionemu.gameserver.services.ai.quest.QuestExecutionState;
import com.aionemu.gameserver.services.ai.quest.QuestExecutionTransition;
import com.aionemu.gameserver.services.ai.quest.QuestNativeSnapshot;
import com.aionemu.gameserver.services.ai.quest.ReadOnlyQuestEligibility;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.taskmanager.tasks.PlayerMoveTaskManager;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.utils.PacketSendUtility;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.WorldPosition;
import com.aionemu.gameserver.world.geo.GeoService;

public final class CompanionService {

	private static final Logger log = LoggerFactory.getLogger(CompanionService.class);

	private final SyntheticPlayerRuntime runtime = SyntheticPlayerRuntime.getInstance();
	private final ServerControlledPlayerRegistry registry = runtime.getRegistry();
	private final SyntheticPlayerScheduler scheduler = runtime.getScheduler();
	private final QuestGoalPlanner goalPlanner = new QuestGoalPlanner();
	private final QuestGoalCandidateProvider goalCandidateProvider = new QuestGoalCandidateProvider();
	private final QuestExecutionPlanProvider executionPlanProvider = new QuestExecutionPlanProvider();
	private final ReadOnlyQuestEligibility goalEligibility = new ReadOnlyQuestEligibility();
	private final CombatContributionOwnerResolver contributionResolver = CombatContributionOwnerResolver.getInstance();
	private final AtomicLong combatSessionIds = new AtomicLong();
	private volatile CompanionSession session;
	private volatile String lastRemovalReason = "";

	private CompanionService() {
	}

	public static CompanionService getInstance() {
		return SingletonHolder.INSTANCE;
	}

	public synchronized ServerControlledPlayer summon(Player owner) {
		CompanionPreflight.requireEnabled(AIConfig.ENABLED, AIConfig.COMPANIONS_ENABLED);
		if (session != null || registry.getActive(SyntheticPlayerRole.COMPANION) != null)
			throw new IllegalStateException("Stage 2A already has an active companion");
		if (AIConfig.COMPANION_TEMPLATE_ACCOUNT_ID <= 0 || AIConfig.COMPANION_TEMPLATE_CHARACTER_ID <= 0)
			throw new IllegalStateException("Companion template account and character IDs must be configured");
		if (!GeoDataConfig.GEO_ENABLE || !GeoDataConfig.CANSEE_ENABLE)
			throw new IllegalStateException("Stage 2A requires geodata and can-see checks");
		CompanionPreflight.validateOwner(owner.getClientConnection() != null,
			World.getInstance().findVisibleObject(owner.getObjectId()) == owner, owner.isSpawned(), runtime.isServerControlled(owner));
		CompanionPreflight.validateSpawnSettings(AIConfig.COMPANION_SPAWN_OFFSET_DISTANCE, AIConfig.COMPANION_COLLISION_TOLERANCE);

		CompanionFollowSettings settings = followSettings();
		int objectId = AIConfig.COMPANION_TEMPLATE_CHARACTER_ID;
		Account account = AccountService.loadAccount(AIConfig.COMPANION_TEMPLATE_ACCOUNT_ID);
		PlayerAccountData templateData = account.getPlayerAccountData(objectId);
		if (templateData == null)
			throw new IllegalArgumentException(
				"Character " + objectId + " does not belong to configured account " + AIConfig.COMPANION_TEMPLATE_ACCOUNT_ID);

		String templateDatabaseName = templateData.getPlayerCommonData().getName();
		String runtimeName = AIConfig.SYNTHETIC_RUNTIME_NAME_PREFIX + templateDatabaseName;
		SyntheticPlayerPreflight.validate(objectId, runtimeName, AIConfig.SYNTHETIC_RUNTIME_NAME_MAX_LENGTH, occupancy(null));
		if (templateData.getPlayerCommonData().isOnline())
			throw new IllegalStateException("Companion template character is marked online in the database");

		Player companion = null;
		ServerControlledPlayer controlledPlayer = null;
		try {
			companion = PlayerService.getPlayer(objectId, account, false);
			validateLoadedPlayer(companion);
			WorldPosition spawnPosition = buildAndValidateSpawnPosition(companion, owner);
			companion.setPosition(spawnPosition);
			companion.getCommonData().setName(runtimeName);

			long combatSessionId = combatSessionIds.incrementAndGet();
			CompanionQuestExecutionRuntime executionRuntime = new CompanionQuestExecutionRuntime(owner.getObjectId(), companion.getObjectId(),
				combatSessionId);
			OwnerAttackSignalSource combatSignalSource = new OwnerAttackSignalSource(owner, companion, combatSessionId, System::currentTimeMillis);
			QuestCombatAuthorizationPolicy questAuthorizationPolicy = new QuestCombatAuthorizationPolicy(owner, companion, combatSessionId,
				executionRuntime, this::isQuestCombatFeatureEnabled, combatSignalSource::isEnabled,
				() -> AIConfig.COMPANION_QUEST_EXECUTION_ALLOWED_IDS);
			CombatActionGateway combatGateway = new DefaultCombatActionGateway(owner, companion, combatSessionId, contributionResolver,
				this::isCombatFeatureEnabled, combatSignalSource::isEnabled, System::currentTimeMillis, AIConfig.COMPANION_COMBAT_OWNER_SIGNAL_TTL_MS,
				AIConfig.COMPANION_COMBAT_ALLOWED_NPC_IDS, questAuthorizationPolicy);
			CompanionCombatController combatController = new CompanionCombatController(owner.getObjectId(), companion.getObjectId(), combatSessionId,
				combatSignalSource, combatGateway, this::isCombatFeatureEnabled, System::currentTimeMillis, AIConfig.COMPANION_COMBAT_OWNER_SIGNAL_TTL_MS);
			CompanionController controller = new CompanionController(new PlayerCompanionContext(owner, companion),
				new DefaultPlayerActionGateway(companion, owner), settings, this::dismissFromRuntime, System::currentTimeMillis, () -> {
					maintainGoalSession();
					executionRuntime.pollIfDue();
					combatController.tick();
				});
			controlledPlayer = new ServerControlledPlayer(companion, templateDatabaseName, runtimeName, SyntheticPlayerRole.COMPANION, controller,
				() -> AIConfig.ENABLED && AIConfig.COMPANIONS_ENABLED, this::dismissFromRuntime);
			CompanionSession newSession = new CompanionSession(owner, controlledPlayer, controller,
				new CompanionGoalSession(owner.getObjectId(), companion.getObjectId()), executionRuntime, combatSessionId, combatController);
			ServerControlledPlayer finalControlledPlayer = controlledPlayer;

			SpawnTransaction.execute(new SpawnTransaction.Steps() {

				@Override
				public void registerWrapper() {
					registry.register(finalControlledPlayer);
					session = newSession;
					contributionResolver.register(finalControlledPlayer.getPlayer(), owner, combatSessionId);
					finalControlledPlayer.markRegistered();
				}

				@Override
				public void storeWorldObject() {
					SyntheticPlayerPreflight.validateAvailable(objectId, runtimeName, occupancy(finalControlledPlayer));
					World.getInstance().storeObject(finalControlledPlayer.getPlayer());
					if (World.getInstance().findVisibleObject(objectId) != finalControlledPlayer.getPlayer()
						|| World.getInstance().getPlayer(objectId) != finalControlledPlayer.getPlayer()
						|| World.getInstance().getPlayer(runtimeName) != finalControlledPlayer.getPlayer())
						throw new IllegalStateException("Companion World/PlayerContainer registration postcondition failed");
				}

				@Override
				public void spawnWorldObject() {
					World.getInstance().spawn(finalControlledPlayer.getPlayer());
					finalControlledPlayer.activate();
				}

				@Override
				public void registerScheduler() {
					assertNoPeriodicSaveTasks(finalControlledPlayer.getPlayer());
					scheduler.register(finalControlledPlayer);
				}

				@Override
				public void rollback() {
					removeRuntimeCompanion(newSession, "summon-rollback");
				}

				@Override
				public void verifyRolledBack() {
					verifyAbsent(newSession);
				}
			});

			lastRemovalReason = "";
			log.info(
				"AI_COMPANION lifecycle=ACTIVE ownerObjectId={} ownerName={} companionObjectId={} templateDbName={} runtimeName={} connectionNull={} mapId={} instanceId={} mode={}",
				owner.getObjectId(), owner.getName(), objectId, templateDatabaseName, runtimeName, companion.getClientConnection() == null,
				companion.getWorldId(), companion.getInstanceId(), controller.getMode());
			return controlledPlayer;
		} catch (RuntimeException | Error e) {
			if (controlledPlayer == null && companion != null)
				releaseRuntimeOnlyReferences(companion, templateDatabaseName);
			throw e;
		}
	}

	public synchronized void follow(Player requester) {
		requireOwner(requester).controller().follow();
	}

	public synchronized void stay(Player requester) {
		requireOwner(requester).controller().stay();
	}

	public synchronized boolean assistOn(Player requester) {
		CompanionSession current = requireOwner(requester);
		requireCombatFeatureEnabled();
		return current.combatController().enableAssist();
	}

	public synchronized boolean assistOff(Player requester) {
		CompanionSession current = requireOwner(requester);
		return current.combatController().disableAssist("admin-command");
	}

	public synchronized String getCombatStatus(Player requester) {
		return requireOwner(requester).combatController().status();
	}

	public synchronized QuestGoalProposalResult proposeGoal(Player requester) {
		requireGoalFeatureEnabled();
		CompanionSession current = requireOwner(requester);
		if (AIConfig.COMPANION_QUEST_EXECUTION_ENABLED) {
			requireExecutionConfiguration();
			var nativeState = requester.getQuestStateList().getQuestState(QuestExecutionAllowlist.FIRST_EXECUTION_QUEST_ID);
			if (nativeState != null && nativeState.getStatus() == com.aionemu.gameserver.questEngine.model.QuestStatus.COMPLETE) {
				current.executionRuntime().clear("already-complete");
				current.goalSession().clear();
				log.info(
					"AI_COMPANION_QUEST_EXECUTION action=PROPOSE result=ALREADY_COMPLETE reason=NATIVE_QUEST_COMPLETE ownerObjectId={} companionObjectId={} companionSessionId={} questId=1102 previousState=NO_GOAL newState=COMPLETED nativeQuestStatus=COMPLETE progress=0 required=3 persistence=false",
					requester.getObjectId(), current.controlledPlayer().getObjectId(), current.combatSessionId());
				return QuestGoalProposalResult.alreadyComplete(QuestExecutionAllowlist.FIRST_EXECUTION_QUEST_ID);
			}
			if (nativeState != null && (nativeState.getStatus() == com.aionemu.gameserver.questEngine.model.QuestStatus.START
				|| nativeState.getStatus() == com.aionemu.gameserver.questEngine.model.QuestStatus.REWARD)) {
				QuestGoalCandidateProvider.CandidateResult existing = goalCandidateProvider.provide(requester,
					QuestExecutionAllowlist.FIRST_EXECUTION_QUEST_ID);
				if (!existing.success())
					throw new IllegalStateException(existing.reason().name());
				executionPlanProvider.build(existing.plan());
				if (current.executionRuntime().clear("new-reattach-offer"))
					logExecutionDetach(current, "new-reattach-offer");
				current.goalSession().offer(existing.plan());
				log.info("AI_COMPANION_GOAL action=OFFERED_REATTACH ownerObjectId={} companionObjectId={} questId={} persistence=false",
					requester.getObjectId(), current.controlledPlayer().getObjectId(), existing.plan().questId());
				return QuestGoalProposalResult.offered(existing.plan());
			}
		}
		QuestGoalPlanner.ProposalResult proposal = buildGoalProposal(requester);
		if (!proposal.success())
			throw new IllegalStateException(proposal.reason() + "; rejections=" + proposal.rejections());
		if (current.executionRuntime().clear("new-offer"))
			logExecutionDetach(current, "new-offer");
		current.goalSession().offer(proposal.plan());
		log.info("AI_COMPANION_GOAL action=OFFERED ownerObjectId={} companionObjectId={} questId={} persistence=false",
			requester.getObjectId(), current.controlledPlayer().getObjectId(), proposal.plan().questId());
		return QuestGoalProposalResult.offered(proposal.plan());
	}

	public synchronized ChoiceResult chooseGoal(Player requester) {
		CompanionSession current = session;
		if (!AIConfig.ENABLED || !AIConfig.COMPANIONS_ENABLED || !AIConfig.COMPANION_QUEST_GOALS_ENABLED) {
			if (current != null) {
				current.goalSession().clear();
				if (current.executionRuntime().clear("goal-feature-disabled"))
					logExecutionDetach(current, "goal-feature-disabled");
			}
			return logChoice(requester, current, ChoiceResult.ineligible(QuestGoalReason.FEATURE_DISABLED));
		}
		if (current == null)
			return logChoice(requester, null, ChoiceResult.noOffer());
		if (current.owner() != requester)
			return logChoice(requester, current, current.goalSession().rejectSessionMismatch());

		ServerControlledPlayer controlled = current.controlledPlayer();
		if (registry.getActive(SyntheticPlayerRole.COMPANION) != controlled || controlled.getState() != SyntheticPlayerState.ACTIVE
			|| current.goalSession().companionObjectId() != controlled.getObjectId())
			return logChoice(requester, current, current.goalSession().rejectSessionMismatch());

		QuestGoalPlan offered = current.goalSession().offered();
		if (offered == null)
			return logChoice(requester, current,
				current.goalSession().choose(requester.getObjectId(), controlled.getObjectId(), null));

		List<Integer> allowedIds;
		try {
			allowedIds = QuestGoalCandidateProvider.parseAllowedQuestIds(AIConfig.COMPANION_QUEST_GOAL_ALLOWED_IDS);
		} catch (IllegalArgumentException e) {
			return logChoice(requester, current,
				current.goalSession().revalidationFailed(requester.getObjectId(), controlled.getObjectId(), QuestGoalReason.INVALID_ALLOWLIST));
		}
		if (!allowedIds.contains(offered.questId()))
			return logChoice(requester, current,
				current.goalSession().revalidationFailed(requester.getObjectId(), controlled.getObjectId(), QuestGoalReason.INVALID_ALLOWLIST));

		QuestGoalCandidateProvider.CandidateResult candidate = goalCandidateProvider.provide(requester, offered.questId());
		if (!candidate.success())
			return logChoice(requester, current,
				current.goalSession().revalidationFailed(requester.getObjectId(), controlled.getObjectId(), candidate.reason()));
		boolean attachExecution = AIConfig.COMPANION_QUEST_EXECUTION_ENABLED;
		var nativeState = requester.getQuestStateList().getQuestState(offered.questId());
		if (attachExecution) {
			try {
				requireExecutionConfiguration();
			} catch (IllegalStateException e) {
				return logChoice(requester, current,
					current.goalSession().revalidationFailed(requester.getObjectId(), controlled.getObjectId(), QuestGoalReason.INVALID_ALLOWLIST));
			}
			if (offered.questId() != QuestExecutionAllowlist.FIRST_EXECUTION_QUEST_ID)
				return logChoice(requester, current,
					current.goalSession().revalidationFailed(requester.getObjectId(), controlled.getObjectId(), QuestGoalReason.INVALID_ALLOWLIST));
			if (nativeState != null && nativeState.getStatus() == com.aionemu.gameserver.questEngine.model.QuestStatus.COMPLETE) {
				current.executionRuntime().clear("already-complete");
				return logChoice(requester, current, current.goalSession().alreadyComplete(offered.questId()));
			}
		}
		boolean reattach = attachExecution && nativeState != null
			&& (nativeState.getStatus() == com.aionemu.gameserver.questEngine.model.QuestStatus.START
				|| nativeState.getStatus() == com.aionemu.gameserver.questEngine.model.QuestStatus.REWARD);
		if (!reattach) {
			ReadOnlyQuestEligibility.EligibilityResult eligibility = goalEligibility.check(requester, offered.questId());
			if (!eligibility.eligible())
				return logChoice(requester, current,
					current.goalSession().revalidationFailed(requester.getObjectId(), controlled.getObjectId(), eligibility.reason()));
		}

		ChoiceResult result = current.goalSession().choose(requester.getObjectId(), controlled.getObjectId(), candidate.plan());
		if (result.status() == ChoiceStatus.CHOSEN && attachExecution)
			attachExecution(current, result.plan());
		return logChoice(requester, current, result);
	}

	private ChoiceResult logChoice(Player requester, CompanionSession current, ChoiceResult result) {
		int companionObjectId = current == null ? 0 : current.controlledPlayer().getObjectId();
		if (result.status() == ChoiceStatus.CHOSEN) {
			log.info("AI_COMPANION_GOAL action=CHOSEN ownerObjectId={} companionObjectId={} questId={} reason={} questAccepted=false persistence=false",
				requester.getObjectId(), companionObjectId, result.questId(), result.reason());
		} else {
			log.warn("AI_COMPANION_GOAL action={} ownerObjectId={} companionObjectId={} questId={} reason={} questAccepted=false persistence=false",
				result.status() == ChoiceStatus.STALE_OFFER ? "STALE" : result.status(), requester.getObjectId(), companionObjectId,
				result.questId(), result.reason());
		}
		return result;
	}

	public synchronized boolean clearGoal(Player requester) {
		CompanionSession current = requireOwner(requester);
		boolean goalChanged = current.goalSession().clear();
		boolean executionChanged = current.executionRuntime().clear("admin-command");
		if (executionChanged)
			logExecutionDetach(current, "admin-command");
		boolean changed = goalChanged || executionChanged;
		log.info("AI_COMPANION_GOAL action=CLEARED ownerObjectId={} companionObjectId={} changed={} reason=admin-command persistence=false",
			requester.getObjectId(), current.controlledPlayer().getObjectId(), changed);
		return changed;
	}

	public synchronized String getGoalStatus(Player requester) {
		CompanionSession current = requireOwner(requester);
		Player companion = current.controlledPlayer().getPlayer();
		QuestExecutionState absentState = current.goalSession().offered() != null && current.goalSession().chosen() == null
			? QuestExecutionState.OFFERED : QuestExecutionState.NO_GOAL;
		return "questGoalFeatureEnabled=" + AIConfig.COMPANION_QUEST_GOALS_ENABLED
			+ ", questExecutionFeatureEnabled=" + isQuestExecutionFeatureEnabled()
			+ ", questCombatFeatureEnabled=" + isQuestCombatFeatureEnabled() + ", " + current.goalSession().status() + ", "
			+ current.executionRuntime().status(System.currentTimeMillis(), requester.getWorldId(), requester.getInstanceId(), companion.getWorldId(),
				companion.getInstanceId(), absentState);
	}

	public synchronized boolean dismiss(Player requester, String reason) {
		CompanionSession current = session;
		if (current == null)
			return false;
		if (current.owner() != requester)
			throw new IllegalStateException("Only the companion owner may dismiss it");
		return dismissSession(current, reason);
	}

	public synchronized void ownerLeaving(Player owner, String reason) {
		CompanionSession current = session;
		if (current != null && current.owner() == owner)
			dismissSession(current, reason);
	}

	public synchronized void shutdown() {
		CompanionSession current = session;
		if (current != null)
			dismissSession(current, "shutdown");
	}

	public synchronized boolean isActiveCompanion(Player player) {
		CompanionSession current = session;
		return current != null && current.controlledPlayer().getPlayer() == player && current.controlledPlayer().getRole() == SyntheticPlayerRole.COMPANION
			&& current.controlledPlayer().getState() == SyntheticPlayerState.ACTIVE
			&& registry.getActive(SyntheticPlayerRole.COMPANION) == current.controlledPlayer()
			&& contributionResolver.matches(player, current.owner(), current.combatSessionId(), true);
	}

	public synchronized void companionDied(Player player, Creature lastAttacker) {
		CompanionSession current = session;
		if (current == null || current.controlledPlayer().getPlayer() != player)
			return;
		String attacker = lastAttacker == null ? "unknown" : Integer.toString(lastAttacker.getObjectId());
		log.info(
			"AI_COMPANION_COMBAT action=SYNTHETIC_DEATH result=CLEANUP_REQUESTED ownerObjectId={} companionObjectId={} lastAttackerObjectId={} persistence=false",
			current.owner().getObjectId(), player.getObjectId(), attacker);
		dismissSession(current, "synthetic-death:last-attacker=" + attacker);
	}

	public String getStatus() {
		CompanionSession current = session;
		if (current == null)
			return "active=false, flags=" + AIConfig.ENABLED + "/" + AIConfig.COMPANIONS_ENABLED + ", assistEnabled=false"
				+ ", attackObserverAttached=false, combatFeatureEnabled=" + isCombatFeatureEnabled() + ", lastRemovalReason=" + lastRemovalReason;

		Player owner = current.owner();
		ServerControlledPlayer controlled = current.controlledPlayer();
		Player companion = controlled.getPlayer();
		QuestExecutionState absentState = current.goalSession().offered() != null && current.goalSession().chosen() == null
			? QuestExecutionState.OFFERED : QuestExecutionState.NO_GOAL;
		boolean worldPresent = World.getInstance().findVisibleObject(companion.getObjectId()) == companion;
		boolean playerContainerPresent = World.getInstance().getPlayer(companion.getObjectId()) == companion
			&& World.getInstance().getPlayer(controlled.getRuntimeName()) == companion;
		double distance = owner.getWorldId() == companion.getWorldId() && owner.getInstanceId() == companion.getInstanceId()
			? PositionUtil.getDistance(owner, companion)
			: Double.NaN;
		return "active=true"
			+ ", ownerObjectId=" + owner.getObjectId()
			+ ", ownerName=" + owner.getName()
			+ ", companionObjectId=" + companion.getObjectId()
			+ ", templateDbName=" + controlled.getTemplateDatabaseName()
			+ ", runtimeName=" + controlled.getRuntimeName()
			+ ", lifecycle=" + controlled.getState()
			+ ", mode=" + current.controller().getMode()
			+ ", clientConnectionNull=" + (companion.getClientConnection() == null)
			+ ", worldPresent=" + worldPresent
			+ ", playerContainerPresent=" + playerContainerPresent
			+ ", spawned=" + companion.isSpawned()
			+ ", schedulerRegistered=" + scheduler.contains(controlled)
			+ ", movementRegistered=" + PlayerMoveTaskManager.getInstance().contains(companion)
			+ ", periodicSaveTasks=" + hasPeriodicSaveTasks(companion)
			+ ", ownerMapId=" + owner.getWorldId()
			+ ", ownerInstanceId=" + owner.getInstanceId()
			+ ", companionMapId=" + companion.getWorldId()
			+ ", companionInstanceId=" + companion.getInstanceId()
			+ ", distanceToOwner=" + distance
			+ ", followStartDistance=" + AIConfig.COMPANION_FOLLOW_START_DISTANCE
			+ ", followStopDistance=" + AIConfig.COMPANION_FOLLOW_STOP_DISTANCE
			+ ", lastBlockedReason=" + current.controller().getLastBlockedReason()
			+ ", combatFeatureEnabled=" + isCombatFeatureEnabled()
			+ ", " + current.combatController().status()
			+ ", questGoalFeatureEnabled=" + AIConfig.COMPANION_QUEST_GOALS_ENABLED
			+ ", " + current.goalSession().status()
			+ ", questExecutionFeatureEnabled=" + isQuestExecutionFeatureEnabled()
			+ ", questCombatFeatureEnabled=" + isQuestCombatFeatureEnabled()
			+ ", " + current.executionRuntime().status(System.currentTimeMillis(), owner.getWorldId(), owner.getInstanceId(), companion.getWorldId(),
				companion.getInstanceId(), absentState)
			+ ", lastRemovalReason=" + lastRemovalReason;
	}

	private synchronized void maintainGoalSession() {
		CompanionSession current = session;
		if (current == null)
			return;
		if (!AIConfig.ENABLED || !AIConfig.COMPANIONS_ENABLED || !AIConfig.COMPANION_QUEST_GOALS_ENABLED) {
			boolean goalChanged = current.goalSession().clear();
			boolean executionChanged = current.executionRuntime().clear("goal-feature-disabled");
			if (goalChanged || executionChanged)
				log.info("AI_COMPANION_GOAL action=CLEARED ownerObjectId={} companionObjectId={} changed=true reason=feature-disabled persistence=false",
					current.owner().getObjectId(), current.controlledPlayer().getObjectId());
		} else if (!isQuestExecutionFeatureEnabled() && current.executionRuntime().clear("quest-execution-disabled")) {
			logExecutionDetach(current, "quest-execution-disabled");
		}
	}

	private boolean isCombatFeatureEnabled() {
		return AIConfig.ENABLED && AIConfig.COMPANIONS_ENABLED && AIConfig.COMPANION_COMBAT_ENABLED && AIConfig.COMPANION_BASIC_ATTACK_ENABLED
			&& AIConfig.COMPANION_OWNER_ATTRIBUTION_ENABLED;
	}

	private boolean isQuestExecutionFeatureEnabled() {
		return AIConfig.ENABLED && AIConfig.COMPANIONS_ENABLED && AIConfig.COMPANION_QUEST_GOALS_ENABLED
			&& AIConfig.COMPANION_QUEST_EXECUTION_ENABLED
			&& QuestExecutionAllowlist.isExactFirstExecutionQuest(AIConfig.COMPANION_QUEST_EXECUTION_ALLOWED_IDS)
			&& isExactExecutionGoalAllowlist() && isValidExecutionPollInterval();
	}

	private boolean isQuestCombatFeatureEnabled() {
		return isCombatFeatureEnabled() && isQuestExecutionFeatureEnabled() && AIConfig.COMPANION_QUEST_EXECUTION_COMBAT_ENABLED;
	}

	private void requireExecutionConfiguration() {
		if (!QuestExecutionAllowlist.isExactFirstExecutionQuest(AIConfig.COMPANION_QUEST_EXECUTION_ALLOWED_IDS))
			throw new IllegalStateException("Quest execution allowlist must contain exact quest 1102");
		if (!isExactExecutionGoalAllowlist())
			throw new IllegalStateException("Quest goal allowlist must contain exact quest 1102 while execution is enabled");
		com.aionemu.gameserver.services.ai.quest.CompanionQuestExecutionTracker.requireValidPollInterval(
			AIConfig.COMPANION_QUEST_EXECUTION_POLL_INTERVAL_MS);
	}

	private boolean isExactExecutionGoalAllowlist() {
		try {
			return QuestGoalCandidateProvider.parseAllowedQuestIds(AIConfig.COMPANION_QUEST_GOAL_ALLOWED_IDS)
				.equals(List.of(QuestExecutionAllowlist.FIRST_EXECUTION_QUEST_ID));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private boolean isValidExecutionPollInterval() {
		try {
			com.aionemu.gameserver.services.ai.quest.CompanionQuestExecutionTracker.requireValidPollInterval(
				AIConfig.COMPANION_QUEST_EXECUTION_POLL_INTERVAL_MS);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private void attachExecution(CompanionSession current, QuestGoalPlan chosen) {
		requireExecutionConfiguration();
		QuestExecutionPlan executionPlan = executionPlanProvider.build(chosen);
		Player owner = current.owner();
		Player companion = current.controlledPlayer().getPlayer();
		current.executionRuntime().attach(executionPlan,
			() -> QuestNativeSnapshot.readOnly(owner.getQuestStateList().getQuestState(executionPlan.questId())),
			() -> new QuestExecutionRuntimeContext(owner.getObjectId(), companion.getObjectId(), current.combatSessionId(),
				QuestExecutionRole.PERSONAL_COMPANION, owner.getWorldId(), owner.getInstanceId(), companion.getWorldId(), companion.getInstanceId(),
				owner.getClientConnection() != null, owner.isSpawned(), companion.isSpawned()),
			transition -> notifyExecutionTransition(current, transition), System::currentTimeMillis,
			AIConfig.COMPANION_QUEST_EXECUTION_POLL_INTERVAL_MS);
		log.info(
			"AI_COMPANION_QUEST_EXECUTION action=ATTACH result=TRACKING reason=GOAL_CHOSEN ownerObjectId={} companionObjectId={} companionSessionId={} questId={} previousState=NO_GOAL newState=TRACKING nativeQuestStatus=read-only progress=read-only required={} persistence=false",
			owner.getObjectId(), companion.getObjectId(), current.combatSessionId(), executionPlan.questId(),
			executionPlan.manifest().requiredProgress());
	}

	private void notifyExecutionTransition(CompanionSession current, QuestExecutionTransition transition) {
		var snapshot = transition.snapshot();
		log.info(
			"AI_COMPANION_QUEST_EXECUTION action=TRACK result=STATE_CHANGED reason={} ownerObjectId={} companionObjectId={} companionSessionId={} questId=1102 previousState={} newState={} nativeQuestStatus={} progress={} required={} persistence=false",
			snapshot.reason(), current.owner().getObjectId(), current.controlledPlayer().getObjectId(), current.combatSessionId(),
			transition.previousState(), transition.newState(), snapshot.nativeQuestStatus() == null ? "ABSENT" : snapshot.nativeQuestStatus(),
			snapshot.currentProgress(), snapshot.requiredProgress());
		PacketSendUtility.sendMessage(current.owner(), QuestExecutionCommandFormatter.formatTransition(transition));
	}

	private void logExecutionDetach(CompanionSession current, String reason) {
		log.info(
			"AI_COMPANION_QUEST_EXECUTION action=DETACH result=CLEARED reason={} ownerObjectId={} companionObjectId={} companionSessionId={} questId=1102 previousState=runtime newState=NO_GOAL nativeQuestStatus=unchanged progress=unchanged required=3 persistence=false",
			reason, current.owner().getObjectId(), current.controlledPlayer().getObjectId(), current.combatSessionId());
	}

	private void requireCombatFeatureEnabled() {
		if (!isCombatFeatureEnabled())
			throw new IllegalStateException("Companion combat feature is disabled");
		if (AIConfig.COMPANION_COMBAT_OWNER_SIGNAL_TTL_MS < 250 || AIConfig.COMPANION_COMBAT_OWNER_SIGNAL_TTL_MS > 10000)
			throw new IllegalStateException("Companion combat owner signal TTL must be between 250 and 10000 ms");
		CombatNpcAllowlist.requireFirstSpikeOnly(AIConfig.COMPANION_COMBAT_ALLOWED_NPC_IDS);
	}

	private void requireGoalFeatureEnabled() {
		CompanionPreflight.requireEnabled(AIConfig.ENABLED, AIConfig.COMPANIONS_ENABLED);
		if (!AIConfig.COMPANION_QUEST_GOALS_ENABLED)
			throw new IllegalStateException(QuestGoalReason.FEATURE_DISABLED.name());
	}

	private QuestGoalPlanner.ProposalResult buildGoalProposal(Player owner) {
		List<Integer> allowedIds = QuestGoalCandidateProvider.parseAllowedQuestIds(AIConfig.COMPANION_QUEST_GOAL_ALLOWED_IDS);
		List<CandidateAssessment> assessments = new ArrayList<>();
		for (int questId : allowedIds) {
			QuestGoalCandidateProvider.CandidateResult candidate = goalCandidateProvider.provide(owner, questId);
			if (!candidate.success()) {
				assessments.add(CandidateAssessment.reject(questId, candidate.reason()));
				continue;
			}
			ReadOnlyQuestEligibility.EligibilityResult eligibility = goalEligibility.check(owner, questId);
			assessments.add(eligibility.eligible() ? CandidateAssessment.accept(candidate.plan())
				: CandidateAssessment.reject(questId, eligibility.reason()));
		}
		return goalPlanner.propose(owner.getLevel(), assessments);
	}

	private synchronized void dismissFromRuntime(String reason) {
		CompanionSession current = session;
		if (current != null)
			dismissSession(current, reason);
	}

	private boolean dismissSession(CompanionSession current, String reason) {
		RuntimeException cleanupFailure = null;
		try {
			removeRuntimeCompanion(current, reason);
		} catch (RuntimeException e) {
			cleanupFailure = e;
		}
		try {
			verifyAbsent(current);
		} catch (RuntimeException e) {
			if (cleanupFailure == null)
				cleanupFailure = e;
			else
				cleanupFailure.addSuppressed(e);
		}
		if (cleanupFailure != null)
			throw cleanupFailure;
		return true;
	}

	private CompanionSession requireOwner(Player requester) {
		CompanionSession current = session;
		if (current == null)
			throw new IllegalStateException("No active companion");
		if (current.owner() != requester)
			throw new IllegalStateException("Only the companion owner may control it");
		return current;
	}

	private CompanionFollowSettings followSettings() {
		return new CompanionFollowSettings(AIConfig.COMPANION_FOLLOW_START_DISTANCE, AIConfig.COMPANION_FOLLOW_STOP_DISTANCE,
			AIConfig.COMPANION_FOLLOW_OFFSET_DISTANCE, AIConfig.COMPANION_DESTINATION_UPDATE_INTERVAL_MS,
			AIConfig.COMPANION_BLOCKED_RETRY_INTERVAL_MS);
	}

	private WorldPosition buildAndValidateSpawnPosition(Player companion, Player owner) {
		World world = World.getInstance();
		GeoService geo = GeoService.getInstance();
		int mapId = owner.getWorldId();
		int instanceId = owner.getInstanceId();
		double behindAngle = Math.toRadians(PositionUtil.convertHeadingToAngle(owner.getHeading()) + 180);
		float targetX = owner.getX() + (float) Math.cos(behindAngle) * AIConfig.COMPANION_SPAWN_OFFSET_DISTANCE;
		float targetY = owner.getY() + (float) Math.sin(behindAngle) * AIConfig.COMPANION_SPAWN_OFFSET_DISTANCE;
		float targetZ = geo.getZ(mapId, targetX, targetY, owner.getZ(), instanceId);
		if (!Float.isFinite(targetZ))
			throw new IllegalArgumentException("Companion spawn point has no geodata surface");

		WorldPosition ownerPosition = world.createPosition(mapId, owner.getX(), owner.getY(), owner.getZ(), owner.getHeading(), instanceId);
		WorldPosition targetPosition = world.createPosition(mapId, targetX, targetY, targetZ,
			PositionUtil.getHeadingTowards(targetX, targetY, owner.getX(), owner.getY()), instanceId);
		companion.setPosition(ownerPosition);
		float angle = PositionUtil.calculateAngleFrom(owner.getX(), owner.getY(), targetX, targetY);
		float distance = (float) PositionUtil.getDistance(owner.getX(), owner.getY(), owner.getZ(), targetX, targetY, targetZ);
		Vector3f reachable = geo.findMovementCollision(companion, angle, distance);
		double remaining = PositionUtil.getDistance(reachable.getX(), reachable.getY(), reachable.getZ(), targetX, targetY, targetZ);
		if (remaining > AIConfig.COMPANION_COLLISION_TOLERANCE)
			throw new IllegalArgumentException("Companion spawn segment is blocked by collision");
		if (!geo.canSee(companion, targetX, targetY, targetZ, IgnoreProperties.ANY_RACE))
			throw new IllegalArgumentException("Companion spawn point is outside line of sight");
		return targetPosition;
	}

	private void validateLoadedPlayer(Player player) {
		if (player.getClientConnection() != null)
			throw new IllegalStateException("Companion template unexpectedly has an AionConnection");
		if (player.getPlayerAppearance() == null || player.getKnownList() == null || player.getPlayerSettings() == null || player.getAbyssRank() == null
			|| player.getMotions() == null || player.getGameStats() == null || player.getEquipment() == null || player.getPosition() == null)
			throw new IllegalStateException("Companion template is not fully initialized for player packet presentation");
		assertNoPeriodicSaveTasks(player);
	}

	private void removeRuntimeCompanion(CompanionSession current, String reason) {
		ServerControlledPlayer controlled = current.controlledPlayer();
		Player companion = controlled.getPlayer();
		controlled.beginRemoval();
		contributionResolver.beginRemoval(companion, current.combatSessionId());
		current.combatController().beginRemoval(reason);
		current.controller().beginRemoval();
		current.goalSession().clear();
		if (current.executionRuntime().clear(reason))
			logExecutionDetach(current, reason);
		List<RuntimeException> failures = new ArrayList<>();
		attemptCleanup("scheduler", () -> scheduler.unregister(controlled), failures);
		attemptCleanup("movement", controlled::stopMovement, failures);
		attemptCleanup("target", () -> companion.setTarget(null), failures);
		attemptCleanup("life-tasks", () -> companion.getLifeStats().cancelAllTasks(), failures);
		attemptCleanup("world", () -> {
			if (World.getInstance().findVisibleObject(companion.getObjectId()) == companion)
				World.getInstance().removeObject(companion);
		}, failures);
		if (World.getInstance().findVisibleObject(companion.getObjectId()) != companion) {
			attemptCleanup("registry", () -> registry.unregister(controlled), failures);
			if (session == current)
				session = null;
			controlled.markRemoved();
			attemptCleanup("runtime-references", () -> releaseRuntimeOnlyReferences(companion, controlled.getTemplateDatabaseName()), failures);
			attemptCleanup("combat-attribution", () -> contributionResolver.unregister(companion, current.combatSessionId()), failures);
		}
		lastRemovalReason = reason;
		log.info(
			"AI_COMPANION lifecycle={} reason={} ownerObjectId={} ownerName={} companionObjectId={} runtimeName={} connectionNull={} worldPresent={} schedulerRegistered={} movementRegistered={}",
			controlled.getState(), reason, current.owner().getObjectId(), current.owner().getName(), companion.getObjectId(), controlled.getRuntimeName(),
			companion.getClientConnection() == null, World.getInstance().findVisibleObject(companion.getObjectId()) == companion,
			scheduler.contains(controlled), PlayerMoveTaskManager.getInstance().contains(companion));
		if (!failures.isEmpty()) {
			IllegalStateException failure = new IllegalStateException("Companion cleanup failed in " + failures.size() + " subsystem(s)");
			failures.forEach(failure::addSuppressed);
			throw failure;
		}
	}

	private void attemptCleanup(String subsystem, Runnable cleanup, List<RuntimeException> failures) {
		try {
			cleanup.run();
		} catch (RuntimeException e) {
			log.error("Companion cleanup failed in {}", subsystem, e);
			failures.add(e);
		}
	}

	private void verifyAbsent(CompanionSession current) {
		ServerControlledPlayer controlled = current.controlledPlayer();
		Player companion = controlled.getPlayer();
		List<String> retainedBy = new ArrayList<>();
		if (World.getInstance().findVisibleObject(companion.getObjectId()) == companion)
			retainedBy.add("World");
		if (World.getInstance().getPlayer(companion.getObjectId()) == companion
			|| World.getInstance().getPlayer(controlled.getRuntimeName()) == companion)
			retainedBy.add("PlayerContainer");
		if (registry.contains(controlled))
			retainedBy.add("registry");
		if (scheduler.contains(controlled))
			retainedBy.add("scheduler");
		if (PlayerMoveTaskManager.getInstance().contains(companion))
			retainedBy.add("movementManager");
		if (session == current)
			retainedBy.add("companionSession");
		if (current.combatController().isObserverAttached())
			retainedBy.add("ownerAttackObserver");
		if (contributionResolver.isPersonalCompanion(companion))
			retainedBy.add("combatAttribution");
		if (current.executionRuntime().isPresent())
			retainedBy.add("questExecutionRuntime");
		if (!retainedBy.isEmpty())
			throw new IllegalStateException("Companion cleanup incomplete; retained by " + retainedBy);
	}

	private void releaseRuntimeOnlyReferences(Player player, String templateDatabaseName) {
		player.getCommonData().setName(templateDatabaseName);
		player.getInventory().setOwner(null);
		player.getWarehouse().setOwner(null);
		player.getAccount().getAccountWarehouse().setOwner(null);
	}

	private void assertNoPeriodicSaveTasks(Player player) {
		if (hasPeriodicSaveTasks(player))
			throw new IllegalStateException("Companion must not receive periodic save tasks");
	}

	private boolean hasPeriodicSaveTasks(Player player) {
		return player.getController().hasTask(TaskId.PLAYER_UPDATE) || player.getController().hasTask(TaskId.INVENTORY_UPDATE);
	}

	private SyntheticPlayerPreflight.Occupancy occupancy(ServerControlledPlayer expectedRegistryEntry) {
		return new SyntheticPlayerPreflight.Occupancy() {

			@Override
			public Object worldObjectById(int objectId) {
				return World.getInstance().findVisibleObject(objectId);
			}

			@Override
			public Object playerById(int objectId) {
				return World.getInstance().getPlayer(objectId);
			}

			@Override
			public Object playerByName(String name) {
				Player exact = World.getInstance().getPlayer(name);
				if (exact != null)
					return exact;
				return World.getInstance().getAllPlayers().stream().filter(p -> p.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
			}

			@Override
			public boolean registryContains(int objectId) {
				return registry.contains(objectId)
					&& (expectedRegistryEntry == null || expectedRegistryEntry.getObjectId() != objectId || !registry.contains(expectedRegistryEntry));
			}

			@Override
			public boolean schedulerContains(int objectId) {
				return scheduler.contains(objectId);
			}

			@Override
			public boolean movementContains(int objectId) {
				return PlayerMoveTaskManager.getInstance().containsObjectId(objectId);
			}
		};
	}

	private record CompanionSession(Player owner, ServerControlledPlayer controlledPlayer, CompanionController controller,
		CompanionGoalSession goalSession, CompanionQuestExecutionRuntime executionRuntime, long combatSessionId,
		CompanionCombatController combatController) {
	}

	private static final class SingletonHolder {

		private static final CompanionService INSTANCE = new CompanionService();
	}
}
