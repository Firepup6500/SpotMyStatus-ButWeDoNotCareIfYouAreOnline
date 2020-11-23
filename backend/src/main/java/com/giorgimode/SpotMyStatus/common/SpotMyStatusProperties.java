package com.giorgimode.SpotMyStatus.common;

import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties("spotmystatus")
@Component
@Getter
@Setter
public class SpotMyStatusProperties {

    private Integer passivePollingProbability;
    private Integer passivateAfterMin;
    private Integer syncStartHr;
    private Integer syncEndHr;
    private Integer timeout;
    private Integer expirationOverhead;
    private List<String> defaultEmojis;
    private String podcastEmoji;
    private Map<String, String> defaultSpotifyItems;
}
