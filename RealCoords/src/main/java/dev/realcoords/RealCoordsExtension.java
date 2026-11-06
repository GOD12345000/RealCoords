package dev.realcoords;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.session.GeyserSession;

public class RealCoordsExtension implements Extension {
    private volatile ShiftConfig cfg;

    @Subscribe
    public void onPostInit(GeyserPostInitializeEvent event) {
        try {
            cfg = ShiftConfig.load(this.dataFolder(), getClass(), this.logger());
            this.logger().info("[RealCoords] 已載入設定。origin-shift=" + cfg.enabled
                    + ", layer=" + cfg.layer + ", actionbar=" + cfg.abEnabled + ", chunk-cache=" + cfg.cacheEnabled);
        } catch (Exception e) {
            this.logger().error("[RealCoords] 載入 config.yml 失敗，擴充停用", e);
        }
    }

    @Subscribe
    public void onLogin(SessionLoginEvent event) {
        ShiftConfig c = cfg;
        if (c == null || !c.enabled) return;
        GeyserSession session = (GeyserSession) event.connection();
        try {
            // Keep the existing Bedrock hook active while the Java hook is only a non-mutating probe.
            SessionHook.install(session, c, this.logger());
            JavaSessionHook.install(session, this.logger());
        } catch (Throwable t) {
            this.logger().error("[RealCoords] 無法啟動封包 hook（該玩家將不套用偏移）", t);
        }
    }
}
