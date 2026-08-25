package com.ethan.pvpstats;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PvPStatsMod implements ModInitializer {
    public static final String MOD_ID = "pvp-stats";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        PvPManager.register();
        LOGGER.info("[PvPStats] PVP 战绩统计 mod 已加载");
    }
}
