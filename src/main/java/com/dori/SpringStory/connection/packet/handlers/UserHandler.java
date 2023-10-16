package com.dori.SpringStory.connection.packet.handlers;

import com.dori.SpringStory.client.MapleClient;
import com.dori.SpringStory.client.character.MapleChar;
import com.dori.SpringStory.client.character.attack.AttackInfo;
import com.dori.SpringStory.connection.packet.Handler;
import com.dori.SpringStory.connection.packet.InPacket;
import com.dori.SpringStory.connection.packet.headers.InHeader;
import com.dori.SpringStory.connection.packet.packets.CUserRemote;
import com.dori.SpringStory.connection.packet.packets.CWvsContext;
import com.dori.SpringStory.constants.GameConstants;
import com.dori.SpringStory.enums.*;
import com.dori.SpringStory.logger.Logger;
import com.dori.SpringStory.utils.SkillUtils;
import com.dori.SpringStory.utils.utilEntities.Position;
import com.dori.SpringStory.world.fieldEntities.Field;
import com.dori.SpringStory.world.fieldEntities.Portal;
import com.dori.SpringStory.world.fieldEntities.movement.MovementData;
import com.dori.SpringStory.wzHandlers.SkillDataHandler;
import com.dori.SpringStory.wzHandlers.wzEntities.SkillData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static com.dori.SpringStory.connection.packet.headers.InHeader.*;
import static com.dori.SpringStory.enums.AttackType.*;
import static com.dori.SpringStory.enums.Skills.NIGHTLORD_SPIRIT_JAVELIN;
import static com.dori.SpringStory.enums.Skills.PRIEST_DISPEL;

public class UserHandler {
    @Handler(op = UserMove)
    public static void handleUserMove(MapleClient c, InPacket inPacket) {
        // CVecCtrlUser::EndUpdateActive
        MapleChar chr = c.getChr();
        Field field = chr.getField();

        inPacket.decodeInt(); // dr0
        inPacket.decodeInt(); // dr1
        inPacket.decodeByte(); // Field Key
        inPacket.decodeInt(); // dr2
        inPacket.decodeInt(); // dr3
        inPacket.decodeInt(); // CRC
        inPacket.decodeInt(); // dwKey
        inPacket.decodeInt(); // CRC32

        // CMovePath::Flush -> CMovePath::Encode (line 85)
        MovementData movementInfo = new MovementData(inPacket);
        movementInfo.applyTo(chr);

        //TODO: need to handle char inAffectedArea -
        //field.checkCharInAffectedAreas(chr);

        // Handle sending player move to other players -
        field.broadcastPacket(CUserRemote.move(chr, movementInfo), chr);
        // Fail-safe when the char falls outside the map
        if (chr.getPosition().getY() > 5000) {
            Portal portal = field.findDefaultPortal();
            chr.warp(chr.getField(), portal);
        }
        // client has stopped moving. this might not be the best way
        if (chr.getMoveAction() == 4 || chr.getMoveAction() == 5) {
            //TODO: need to handle TSM (Temporary stat manager)
        }
    }

    @Handler(ops = {UserMeleeAttack, UserShootAttack, UserMagicAttack, UserBodyAttack})
    public static void handleUserAttack(MapleClient c, InPacket inPacket, InHeader header) {
        // CUserLocal::TryDoingMeleeAttack -> line 2878
        MapleChar chr = c.getChr();
        AttackInfo ai = new AttackInfo();
        AttackType type = None;
        switch (header) {
            case UserMeleeAttack -> type = Melee;
            case UserShootAttack -> type = Shoot;
            case UserMagicAttack -> type = Magic;
            case UserBodyAttack -> type = Body;
        }
        if (type != None) {
            ai.decode(type, inPacket);
            chr.getField().broadcastPacket(CUserRemote.attack(chr, ai), chr);
            ai.apply(chr);
        }
    }

    @Handler(op = UserHit)
    public static void handleUserHit(MapleClient c, InPacket inPacket) {
        MapleChar chr = c.getChr();
        // CUserLocal::SetDamaged - Line 637
        inPacket.decodeInt(); // timeStamp
        DamageType type = DamageType.getTypeByVal(inPacket.decodeByte());
        inPacket.decodeByte(); // magicElemAttr | Element - 0x00 = element-less, 0x01 = ice, 0x02 = fire, 0x03 = lightning
        int dmg = inPacket.decodeInt();
        int mobID = 0;
        boolean isLeft = false;

        switch (type) {
            case Physical, Magic -> {
                mobID = inPacket.decodeInt();
                int objID = inPacket.decodeInt();
                isLeft = inPacket.decodeBool();
                byte top = inPacket.decodeByte();
                byte relativeDir = inPacket.decodeByte();
                byte damageMissed = inPacket.decodeByte();
                byte nX = inPacket.decodeByte();
            }
        }
        chr.getField().broadcastPacket(CUserRemote.hit(chr, type, dmg, mobID, isLeft));
        chr.modifyHp(-dmg);
    }

    @Handler(op = QuickslotKeyMappedModified)
    public static void handleQuickSlotKeyMappedModified(MapleClient c, InPacket inPacket) {
        int length = GameConstants.QUICK_SLOT_LENGTH;
        List<Integer> quickSlotKeys = new ArrayList<>();
        for (int i = 0; i <= length; i++) {
            quickSlotKeys.add(inPacket.decodeInt());
        }
        c.getChr().setQuickSlotKeys(quickSlotKeys);
        //TODO:funcKeyMappedManInit!!!!
    }

    @Handler(op = UserSkillUpRequest)
    public static void handleUserSkillUpRequest(MapleClient c, InPacket inPacket) {
        // CWvsContext::SendSkillUpRequest
        MapleChar chr = c.getChr();
        inPacket.decodeInt(); // update_time
        int skillID = inPacket.decodeInt(); // update_time
        chr.lvlUpSkill(skillID);
        //TODO: need to remove!!
        chr.message("SkillID: " + skillID, ChatType.SpeakerWorld);
        SkillData sd = SkillDataHandler.getSkillDataByID(skillID);
        sd.getSkillStatInfo().forEach((stat, val) -> chr.message(stat + ": " + val, ChatType.SpeakerWorld));
        chr.write(CWvsContext.changeSkillRecordResult(chr.getSkills(), true, true));
        //TODO: need to handle certain passive skills -> stat boost / hp|mp boost! | recovery? for example 1110000 (suppose to give more mp recovry but i gues it's totally server sided?)
    }

    @Handler(ops = {UserChangeStatRequest, UserChangeStatRequestByItemOption})
    public static void handleUserChangeStatRequest(MapleClient c, InPacket inPacket, InHeader header) {
        MapleChar chr = c.getChr();
        inPacket.decodeInt(); // update time
        inPacket.decodeInt(); // mask seems always to be 1400 -> 1000 + 400 | honestly idk why you need it cause you only encode hp & mp always?
        short hp = inPacket.decodeShort();
        short mp = inPacket.decodeShort();
        inPacket.decodeByte(); // nOption | Maybe related if you sit or not?, seems that if you sit it's 2? (default is 0)
        if (hp > 0 || mp > 0) {
            HashMap<Stat, Object> stats = new HashMap<>();
            if (hp > 0) {
                chr.setHp(Math.min(chr.getMaxHp(), chr.getHp() + hp));
                stats.put(Stat.Hp, chr.getHp());
            }
            if (mp > 0) {
                chr.setMp(Math.min(chr.getMaxMp(), chr.getMp() + mp));
                stats.put(Stat.Mp, chr.getMp());
            }
            chr.changeStats(stats);
        }
    }

    @Handler(op = PassiveskillInfoUpdate)
    public static void handleUserPassiveSkillInfoUpdate(MapleClient c, InPacket inPacket) {
        inPacket.decodeInt(); // updateTime
        // The most not relevant packet i've seen in sometime xD
    }

    @Handler(op = UserSkillUseRequest)
    public static void handleUserSkillUseRequest(MapleClient c, InPacket inPacket) {
        // CUserLocal::SendSkillUseRequest -> Line 176
        inPacket.decodeInt(); // update_time
        int skillID = inPacket.decodeInt();
        byte slv = inPacket.decodeByte();
        if (SkillUtils.isAntiRepeatBuffSkill(skillID)) {
            // Anti-repeat buff skill -
            Position chrPos = inPacket.decodePosition();
            if (skillID == NIGHTLORD_SPIRIT_JAVELIN.getId()) {
                int nSpiritJavelinItemID = inPacket.decodeInt();
            }
            // TODO: can be mapped by each skill in the WZ files!
            if (false) { // dwAffectedMemberBitmap
                byte dwAffectedMemberBitmap = inPacket.decodeByte(); // it's a byte map of the effected members from the party that will receive the buff
                if (skillID == PRIEST_DISPEL.getId()) {
                    short tDelay = inPacket.decodeShort();
                }
            }
        }
        if (false) { // inPacket.getUnreadAmount() > 2
            byte nMobCount = inPacket.decodeByte();
            for (int i = 0; i < nMobCount; i++) {
                int adwMobID = inPacket.decodeInt();
            }
            int tDelay = inPacket.decodeShort();
        }
        // Handle the skill cts -
        c.getChr().handleSkill(skillID, slv);

        /**
         * OKay so i've finally finished thinking how to handle cts -
         * each char will have a concurrent <Skill,Indie> ID (Tuple but i will join both values to 1 num to have better performance) and a timestamp for expiring
         * when a skill occur i will add into list/queue an event for checking the chr temp stats for updating state. this way even if the player re-buff i can manage the updates without
         * concurrency issues!
         *
         * need to add for hp/mp custom handling to make note of base stat + passive bonuses!
         */
    }
}
