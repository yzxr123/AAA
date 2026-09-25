package org.adguardian.app.litiaotiao;

final class LiTiaotiaoRule {
    final String id;
    final String action;
    final Integer times;
    final Integer delayPopupMs;

    LiTiaotiaoRule(String id, String action, Integer times, Integer delayPopupMs) {
        this.id = id == null ? "" : id;
        this.action = action == null ? "" : action;
        this.times = times;
        this.delayPopupMs = delayPopupMs;
    }
}
