package com.aionemu.gameserver.services.ai.creation;

import java.sql.Timestamp;

import com.aionemu.gameserver.model.Gender;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.model.account.PlayerAccountData;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerAppearance;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;
import com.aionemu.gameserver.services.AccountService;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.utils.idfactory.IDFactory;

public final class DefaultCompanionPlayerCreationGateway implements CompanionPlayerCreationGateway {

	@Override
	public CompanionBodyRecord create(CompanionCreationRequest request, String databaseName, int hostAccountId, String hostAccountName) {
		Account hostAccount = AccountService.loadAccount(hostAccountId);
		hostAccount.setName(hostAccountName);
		int objectId = IDFactory.getInstance().nextId();
		PlayerCommonData commonData = new PlayerCommonData(objectId);
		commonData.setName(databaseName);
		commonData.setGender(Gender.valueOf(request.gender()));
		commonData.setRace(Race.valueOf(request.race()));
		commonData.setPlayerClass(PlayerClass.valueOf(CompanionCreationPolicy.STARTING_CLASS));
		commonData.setLevel(1);
		PlayerAppearance appearance = new PlayerAppearance();
		appearance.setHeight(1.0f);
		PlayerAccountData accountData = new PlayerAccountData(commonData, appearance);
		Player player = PlayerService.newPlayer(accountData, hostAccount);
		if (!PlayerService.storeNewPlayer(player, hostAccountName, hostAccountId))
			throw new IllegalStateException("Standard player creation pipeline could not persist companion body " + databaseName);
		Timestamp createdAt = new Timestamp(System.currentTimeMillis());
		accountData.setCreationDate(createdAt);
		PlayerService.storeCreationTime(objectId, createdAt);
		return new CompanionBodyRecord(objectId, databaseName, hostAccountId, hostAccountName, request.race(), request.gender(),
			CompanionCreationPolicy.STARTING_CLASS, false, true);
	}
}
