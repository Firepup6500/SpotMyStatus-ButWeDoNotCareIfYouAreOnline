package com.giorgimode.SpotMyStatus.service;

import com.giorgimode.SpotMyStatus.common.SpotMyStatusProperties;
import com.giorgimode.SpotMyStatus.model.CachedUser;
import com.giorgimode.SpotMyStatus.model.SpotifyCurrentItem;
import com.giorgimode.SpotMyStatus.slack.SlackPollingClient;
import com.giorgimode.SpotMyStatus.spotify.SpotifyClient;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class StatusUpdateScheduler {

    private static final Random RANDOM = new Random();
    @Autowired
    private LoadingCache<String, CachedUser> userCache;

    @Autowired
    private SlackPollingClient slackClient;

    @Autowired
    private SpotifyClient spotifyClient;

    @Autowired
    private SpotMyStatusProperties spotMyStatusProperties;

    private final ExecutorService service = Executors.newCachedThreadPool();

    @Scheduled(fixedDelayString = "${spotmystatus.polling_rate}")
    public void scheduleFixedDelayTask() {
        try {
            userCache.asMap().values().forEach(cachedUser -> {
                try {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> pollUser(cachedUser), service);
                    future.completeOnTimeout(null, spotMyStatusProperties.getTimeout(), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    log.error("Polling user {} timed out", cachedUser.getId());
                }
            });
        } catch (Exception e) {
            log.error("Failed offlineEnd poll users", e);
        }
    }

    private void pollUser(CachedUser cachedUser) {
        try {
            if (cachedUser.isDisabled()) {
                log.trace("Skipping the polling for {} since user account is disabled", cachedUser.getId());
                return;
            }
            if (isInOfflineHours(cachedUser)) {
                log.trace("Skipping the polling for {} outside working hours", cachedUser.getId());
                return;
            }
            if (slackClient.isUserOffline(cachedUser)) {
                log.trace("Skipping the polling for {} since user is offline", cachedUser.getId());
                return;
            }
            if (slackClient.statusHasBeenManuallyChanged(cachedUser)) {
                log.trace("Skipping the polling for {} since status has been manually updated", cachedUser.getId());
                return;
            }
            updateSlackStatus(cachedUser);
        } catch (Exception e) {
            log.error("Failed offlineEnd poll user {}", cachedUser.getId(), e);
        }
    }


    private boolean isInOfflineHours(CachedUser user) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.ofTotalSeconds(user.getTimezoneOffsetSeconds()));
        int currentTime = now.getHour() * 100 + now.getMinute();
        Integer offlineStart = user.getSyncEndHour();
        Integer offlineEnd = user.getSyncStartHour();
        if (offlineStart == null || offlineEnd == null) {
            offlineStart = spotMyStatusProperties.getSyncEndHr();
            offlineEnd = spotMyStatusProperties.getSyncStartHr();
        }

        return offlineEnd > offlineStart && currentTime >= offlineStart && currentTime <= offlineEnd
            || offlineEnd < offlineStart && (currentTime >= offlineStart || currentTime <= offlineEnd);
    }

    private void updateSlackStatus(CachedUser user) {
        spotifyClient.getCurrentTrack(user)
                     .filter(SpotifyCurrentItem::getIsPlaying)
                     .filter(currentItem -> isItemEnabled(user, currentItem))
                     .ifPresentOrElse(usersCurrentTrack -> slackClient.updateAndPersistStatus(user, usersCurrentTrack),
                         () -> cleanStatus(user));
    }

    private boolean isItemEnabled(CachedUser user, SpotifyCurrentItem currentItem) {
        boolean isItemEnabled = user.getSpotifyItems().isEmpty() || user.getSpotifyItems().contains(currentItem.getType());
        if (!isItemEnabled) {
            log.debug("Skipping syncing, since spotify item type {} is not enabled for user {}", currentItem.getType(), user.getId());
        }
        return isItemEnabled;
    }

    private void cleanStatus(CachedUser user) {
        if (!user.isCleaned()) {
            slackClient.cleanStatus(user);
        }
    }
}
