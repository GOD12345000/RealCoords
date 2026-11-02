package dev.realcoords;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.session.GeyserSession;

public class RealCoordsExtension implements Extension {
    private volatile ShiftConfig cfg;
    private volatile RealCoordsLog realLog;

    @Subscribe
    public void onPostInit(GeyserPostInitializeEvent event) {
        try {
            realLog = new RealCoordsLog(this.dataFolder(), this.logger());
            cfg = ShiftConfig.load(this.dataFolder(), getClass(), this.logger());
            realLog.info("[RealCoords] 已載入設定。origin-shift=" + cfg.enabled
                    + ", actionbar=" + cfg.abEnabled + ", chunk-cache=" + cfg.cacheEnabled);
        } catch (Exception e) {
            this.logger().error("[RealCoords] 載入 config.yml 失敗，擴充停用", e);
        }
    }

    @Subscribe
    public void onJoin(SessionJoinEvent event) {
        ShiftConfig c = cfg;
        if (c == null || !c.enabled) return;
        try {
            SessionHook.installJava((GeyserSession) event.connection());
        } catch (Throwable t) {
            if (realLog != null) realLog.error("[RealCoords] 無法掛上 Java 下游封包轉換器", t);
            else this.logger().error("[RealCoords] 無法掛上 Java 下游封包轉換器", t);
        }
    }

    @Subscribe
    public void onLogin(SessionLoginEvent event) {
        ShiftConfig c = cfg;
        if (c == null || !c.enabled) return;
        try {
            SessionHook.install((GeyserSession) event.connection(), c, realLog);
        } catch (Throwable t) {
            if (realLog != null) realLog.error("[RealCoords] 無法掛上封包轉換器（該玩家將不套用偏移）", t);
            else this.logger().error("[RealCoords] 無法掛上封包轉換器（該玩家將不套用偏移）", t);
        }
    }
}
