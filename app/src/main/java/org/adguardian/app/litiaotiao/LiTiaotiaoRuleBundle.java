package org.adguardian.app.litiaotiao;

import java.util.Collections;
import java.util.List;

final class LiTiaotiaoRuleBundle {
    final boolean keywordsSpecified;
    final List<String> keywords;
    final List<String> keywordsAppend;
    final List<LiTiaotiaoRule> popupRules;
    final int clickWay;
    final int clickWayPopup;
    final int searchTimesPopup;
    final int delayMs;
    final int delayPopupMs;
    final int times;
    final boolean unitePopupRules;
    final boolean lttService;

    LiTiaotiaoRuleBundle(
            boolean keywordsSpecified,
            List<String> keywords,
            List<String> keywordsAppend,
            List<LiTiaotiaoRule> popupRules,
            int clickWay,
            int clickWayPopup,
            int searchTimesPopup,
            int delayMs,
            int delayPopupMs,
            int times,
            boolean unitePopupRules,
            boolean lttService
    ) {
        this.keywordsSpecified = keywordsSpecified;
        this.keywords = Collections.unmodifiableList(keywords);
        this.keywordsAppend = Collections.unmodifiableList(keywordsAppend);
        this.popupRules = Collections.unmodifiableList(popupRules);
        this.clickWay = clickWay;
        this.clickWayPopup = clickWayPopup;
        this.searchTimesPopup = searchTimesPopup;
        this.delayMs = delayMs;
        this.delayPopupMs = delayPopupMs;
        this.times = times;
        this.unitePopupRules = unitePopupRules;
        this.lttService = lttService;
    }
}
