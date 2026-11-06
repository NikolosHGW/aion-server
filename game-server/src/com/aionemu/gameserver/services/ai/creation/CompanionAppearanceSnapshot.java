package com.aionemu.gameserver.services.ai.creation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aionemu.gameserver.model.gameobjects.player.PlayerAppearance;

/** Versioned, appearance-only value snapshot; decoding always returns a new mutable native object. */
public final class CompanionAppearanceSnapshot {
	private CompanionAppearanceSnapshot() {}

	public static String encode(PlayerAppearance appearance) {
		if (appearance == null) throw new IllegalArgumentException("Character appearance is missing");
		JSONObject object = new JSONObject();
		object.put("version", 1);
		object.put("height", appearance.getHeightScale());
		object.put("values", new int[] {
			appearance.getFace(), appearance.getHair(), appearance.getDeco(), appearance.getTattoo(),
			appearance.getFaceContour(), appearance.getExpression(), appearance.getJawLine(), appearance.getSkinRGB(),
			appearance.getHairRGB(), appearance.getEyeRGB(), appearance.getLipRGB(), appearance.getFaceShape(),
			appearance.getForehead(), appearance.getEyeHeight(), appearance.getEyeSpace(), appearance.getEyeWidth(),
			appearance.getEyeSize(), appearance.getEyeShape(), appearance.getEyeAngle(), appearance.getBrowHeight(),
			appearance.getBrowAngle(), appearance.getBrowShape(), appearance.getNose(), appearance.getNoseBridge(),
			appearance.getNoseWidth(), appearance.getNoseTip(), appearance.getCheek(), appearance.getLipHeight(),
			appearance.getMouthSize(), appearance.getLipSize(), appearance.getSmile(), appearance.getLipShape(),
			appearance.getChinHeight(), appearance.getCheekBones(), appearance.getEarShape(), appearance.getHeadSize(),
			appearance.getNeck(), appearance.getNeckLength(), appearance.getShoulders(), appearance.getShoulderSize(),
			appearance.getTorso(), appearance.getChest(), appearance.getWaist(), appearance.getHips(),
			appearance.getArmThickness(), appearance.getArmLength(), appearance.getHandSize(), appearance.getLegThickness(),
			appearance.getLegLength(), appearance.getFootSize(), appearance.getFacialRate(), appearance.getVoice()
		});
		String value = JSON.toJSONString(object);
		decode(value);
		return value;
	}

	public static PlayerAppearance decode(String value) {
		if (value == null || value.length() > 8192) throw new IllegalArgumentException("Invalid appearance snapshot");
		JSONObject object = JSON.parseObject(value);
		if (object == null || !object.keySet().equals(Set.of("version", "height", "values"))
				|| !Integer.valueOf(1).equals(object.get("version"))) throw new IllegalArgumentException("Unknown appearance schema");
		JSONArray values = object.getJSONArray("values");
		if (values == null || values.size() != 52) throw new IllegalArgumentException("Incomplete appearance snapshot");
		for (int i = 0; i < values.size(); i++) {
			if (!(values.get(i) instanceof Integer n)) throw new IllegalArgumentException("Invalid appearance field");
			if (i != 7 && i != 8 && i != 9 && i != 10 && i != 51 && (n < 0 || n > 255)) throw new IllegalArgumentException("Invalid appearance slider");
		}
		float height = object.getFloatValue("height");
		if (!Float.isFinite(height) || height < 0.65f || height > 1.2f) throw new IllegalArgumentException("Invalid character height");
		PlayerAppearance appearance = new PlayerAppearance();
		appearance.setFace(values.getIntValue(0));
		appearance.setHair(values.getIntValue(1));
		appearance.setDeco(values.getIntValue(2));
		appearance.setTattoo(values.getIntValue(3));
		appearance.setFaceContour(values.getIntValue(4));
		appearance.setExpression(values.getIntValue(5));
		appearance.setJawLine(values.getIntValue(6));
		appearance.setSkinRGB(values.getIntValue(7));
		appearance.setHairRGB(values.getIntValue(8));
		appearance.setEyeRGB(values.getIntValue(9));
		appearance.setLipRGB(values.getIntValue(10));
		appearance.setFaceShape(values.getIntValue(11));
		appearance.setForehead(values.getIntValue(12));
		appearance.setEyeHeight(values.getIntValue(13));
		appearance.setEyeSpace(values.getIntValue(14));
		appearance.setEyeWidth(values.getIntValue(15));
		appearance.setEyeSize(values.getIntValue(16));
		appearance.setEyeShape(values.getIntValue(17));
		appearance.setEyeAngle(values.getIntValue(18));
		appearance.setBrowHeight(values.getIntValue(19));
		appearance.setBrowAngle(values.getIntValue(20));
		appearance.setBrowShape(values.getIntValue(21));
		appearance.setNose(values.getIntValue(22));
		appearance.setNoseBridge(values.getIntValue(23));
		appearance.setNoseWidth(values.getIntValue(24));
		appearance.setNoseTip(values.getIntValue(25));
		appearance.setCheek(values.getIntValue(26));
		appearance.setLipHeight(values.getIntValue(27));
		appearance.setMouthSize(values.getIntValue(28));
		appearance.setLipSize(values.getIntValue(29));
		appearance.setSmile(values.getIntValue(30));
		appearance.setLipShape(values.getIntValue(31));
		appearance.setChinHeight(values.getIntValue(32));
		appearance.setCheekBones(values.getIntValue(33));
		appearance.setEarShape(values.getIntValue(34));
		appearance.setHeadSize(values.getIntValue(35));
		appearance.setNeck(values.getIntValue(36));
		appearance.setNeckLength(values.getIntValue(37));
		appearance.setShoulders(values.getIntValue(38));
		appearance.setShoulderSize(values.getIntValue(39));
		appearance.setTorso(values.getIntValue(40));
		appearance.setChest(values.getIntValue(41));
		appearance.setWaist(values.getIntValue(42));
		appearance.setHips(values.getIntValue(43));
		appearance.setArmThickness(values.getIntValue(44));
		appearance.setArmLength(values.getIntValue(45));
		appearance.setHandSize(values.getIntValue(46));
		appearance.setLegThickness(values.getIntValue(47));
		appearance.setLegLength(values.getIntValue(48));
		appearance.setFootSize(values.getIntValue(49));
		appearance.setFacialRate(values.getIntValue(50));
		appearance.setVoice(values.getIntValue(51));
		appearance.setHeightScale(height);
		return appearance;
	}

	/** Strict read: missing rows/columns/NULL fields must not become an invisible default body. */
	public static PlayerAppearance read(ResultSet result) throws SQLException {
		PlayerAppearance appearance = new PlayerAppearance();
		appearance.setFace(result.getInt("face"));
		if (result.wasNull()) throw new SQLException("Missing appearance face");
		appearance.setHair(result.getInt("hair"));
		if (result.wasNull()) throw new SQLException("Missing appearance hair");
		appearance.setDeco(result.getInt("deco"));
		if (result.wasNull()) throw new SQLException("Missing appearance deco");
		appearance.setTattoo(result.getInt("tattoo"));
		if (result.wasNull()) throw new SQLException("Missing appearance tattoo");
		appearance.setFaceContour(result.getInt("face_contour"));
		if (result.wasNull()) throw new SQLException("Missing appearance face_contour");
		appearance.setExpression(result.getInt("expression"));
		if (result.wasNull()) throw new SQLException("Missing appearance expression");
		appearance.setJawLine(result.getInt("jaw_line"));
		if (result.wasNull()) throw new SQLException("Missing appearance jaw_line");
		appearance.setSkinRGB(result.getInt("skin_rgb"));
		if (result.wasNull()) throw new SQLException("Missing appearance skin_rgb");
		appearance.setHairRGB(result.getInt("hair_rgb"));
		if (result.wasNull()) throw new SQLException("Missing appearance hair_rgb");
		appearance.setEyeRGB(result.getInt("eye_rgb"));
		if (result.wasNull()) throw new SQLException("Missing appearance eye_rgb");
		appearance.setLipRGB(result.getInt("lip_rgb"));
		if (result.wasNull()) throw new SQLException("Missing appearance lip_rgb");
		appearance.setFaceShape(result.getInt("face_shape"));
		if (result.wasNull()) throw new SQLException("Missing appearance face_shape");
		appearance.setForehead(result.getInt("forehead"));
		if (result.wasNull()) throw new SQLException("Missing appearance forehead");
		appearance.setEyeHeight(result.getInt("eye_height"));
		if (result.wasNull()) throw new SQLException("Missing appearance eye_height");
		appearance.setEyeSpace(result.getInt("eye_space"));
		if (result.wasNull()) throw new SQLException("Missing appearance eye_space");
		appearance.setEyeWidth(result.getInt("eye_width"));
		if (result.wasNull()) throw new SQLException("Missing appearance eye_width");
		appearance.setEyeSize(result.getInt("eye_size"));
		if (result.wasNull()) throw new SQLException("Missing appearance eye_size");
		appearance.setEyeShape(result.getInt("eye_shape"));
		if (result.wasNull()) throw new SQLException("Missing appearance eye_shape");
		appearance.setEyeAngle(result.getInt("eye_angle"));
		if (result.wasNull()) throw new SQLException("Missing appearance eye_angle");
		appearance.setBrowHeight(result.getInt("brow_height"));
		if (result.wasNull()) throw new SQLException("Missing appearance brow_height");
		appearance.setBrowAngle(result.getInt("brow_angle"));
		if (result.wasNull()) throw new SQLException("Missing appearance brow_angle");
		appearance.setBrowShape(result.getInt("brow_shape"));
		if (result.wasNull()) throw new SQLException("Missing appearance brow_shape");
		appearance.setNose(result.getInt("nose"));
		if (result.wasNull()) throw new SQLException("Missing appearance nose");
		appearance.setNoseBridge(result.getInt("nose_bridge"));
		if (result.wasNull()) throw new SQLException("Missing appearance nose_bridge");
		appearance.setNoseWidth(result.getInt("nose_width"));
		if (result.wasNull()) throw new SQLException("Missing appearance nose_width");
		appearance.setNoseTip(result.getInt("nose_tip"));
		if (result.wasNull()) throw new SQLException("Missing appearance nose_tip");
		appearance.setCheek(result.getInt("cheek"));
		if (result.wasNull()) throw new SQLException("Missing appearance cheek");
		appearance.setLipHeight(result.getInt("lip_height"));
		if (result.wasNull()) throw new SQLException("Missing appearance lip_height");
		appearance.setMouthSize(result.getInt("mouth_size"));
		if (result.wasNull()) throw new SQLException("Missing appearance mouth_size");
		appearance.setLipSize(result.getInt("lip_size"));
		if (result.wasNull()) throw new SQLException("Missing appearance lip_size");
		appearance.setSmile(result.getInt("smile"));
		if (result.wasNull()) throw new SQLException("Missing appearance smile");
		appearance.setLipShape(result.getInt("lip_shape"));
		if (result.wasNull()) throw new SQLException("Missing appearance lip_shape");
		appearance.setChinHeight(result.getInt("chin_height"));
		if (result.wasNull()) throw new SQLException("Missing appearance chin_height");
		appearance.setCheekBones(result.getInt("cheek_bones"));
		if (result.wasNull()) throw new SQLException("Missing appearance cheek_bones");
		appearance.setEarShape(result.getInt("ear_shape"));
		if (result.wasNull()) throw new SQLException("Missing appearance ear_shape");
		appearance.setHeadSize(result.getInt("head_size"));
		if (result.wasNull()) throw new SQLException("Missing appearance head_size");
		appearance.setNeck(result.getInt("neck"));
		if (result.wasNull()) throw new SQLException("Missing appearance neck");
		appearance.setNeckLength(result.getInt("neck_length"));
		if (result.wasNull()) throw new SQLException("Missing appearance neck_length");
		appearance.setShoulders(result.getInt("shoulders"));
		if (result.wasNull()) throw new SQLException("Missing appearance shoulders");
		appearance.setShoulderSize(result.getInt("shoulder_size"));
		if (result.wasNull()) throw new SQLException("Missing appearance shoulder_size");
		appearance.setTorso(result.getInt("torso"));
		if (result.wasNull()) throw new SQLException("Missing appearance torso");
		appearance.setChest(result.getInt("chest"));
		if (result.wasNull()) throw new SQLException("Missing appearance chest");
		appearance.setWaist(result.getInt("waist"));
		if (result.wasNull()) throw new SQLException("Missing appearance waist");
		appearance.setHips(result.getInt("hips"));
		if (result.wasNull()) throw new SQLException("Missing appearance hips");
		appearance.setArmThickness(result.getInt("arm_thickness"));
		if (result.wasNull()) throw new SQLException("Missing appearance arm_thickness");
		appearance.setArmLength(result.getInt("arm_length"));
		if (result.wasNull()) throw new SQLException("Missing appearance arm_length");
		appearance.setHandSize(result.getInt("hand_size"));
		if (result.wasNull()) throw new SQLException("Missing appearance hand_size");
		appearance.setLegThickness(result.getInt("leg_thickness"));
		if (result.wasNull()) throw new SQLException("Missing appearance leg_thickness");
		appearance.setLegLength(result.getInt("leg_length"));
		if (result.wasNull()) throw new SQLException("Missing appearance leg_length");
		appearance.setFootSize(result.getInt("foot_size"));
		if (result.wasNull()) throw new SQLException("Missing appearance foot_size");
		appearance.setFacialRate(result.getInt("facial_rate"));
		if (result.wasNull()) throw new SQLException("Missing appearance facial_rate");
		appearance.setVoice(result.getInt("voice"));
		if (result.wasNull()) throw new SQLException("Missing appearance voice");
		appearance.setHeightScale(result.getFloat("height"));
		if (result.wasNull()) throw new SQLException("Missing appearance height");
		encode(appearance);
		return appearance;
	}
}
