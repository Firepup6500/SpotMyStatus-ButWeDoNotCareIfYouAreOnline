package com.giorgimode.SpotMyStatus.service;

import static com.giorgimode.SpotMyStatus.util.SpotConstants.ACTION_ID_EMOJI_ADD;
import static com.giorgimode.SpotMyStatus.util.SpotConstants.BLOCK_ID_EMOJI_INPUT;
import static com.giorgimode.SpotMyStatus.util.SpotConstants.BLOCK_ID_EMOJI_LIST;
import static com.giorgimode.SpotMyStatus.util.SpotConstants.BLOCK_ID_SPOTIFY_ITEMS;
import static com.giorgimode.SpotMyStatus.util.SpotConstants.BLOCK_ID_SYNC_TOGGLE;
import static com.giorgimode.SpotMyStatus.util.SpotConstants.PAYLOAD_TYPE_BLOCK_ACTIONS;
import static com.giorgimode.SpotMyStatus.util.SpotConstants.PAYLOAD_TYPE_SUBMISSION;
import static com.giorgimode.SpotMyStatus.util.SpotUtil.OBJECT_MAPPER;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import com.giorgimode.SpotMyStatus.common.SpotMyStatusProperties;
import com.giorgimode.SpotMyStatus.exceptions.UserNotFoundException;
import com.giorgimode.SpotMyStatus.model.CachedUser;
import com.giorgimode.SpotMyStatus.model.modals.Action;
import com.giorgimode.SpotMyStatus.model.modals.Block;
import com.giorgimode.SpotMyStatus.model.modals.Option;
import com.giorgimode.SpotMyStatus.model.modals.SlackModalIn;
import com.giorgimode.SpotMyStatus.model.modals.SlackModalOut;
import com.giorgimode.SpotMyStatus.model.modals.SlackModalView;
import com.giorgimode.SpotMyStatus.model.modals.Text;
import com.giorgimode.SpotMyStatus.model.modals.StateValue;
import com.giorgimode.SpotMyStatus.persistence.UserRepository;
import com.giorgimode.SpotMyStatus.slack.SlackPollingClient;
import com.giorgimode.SpotMyStatus.util.RestHelper;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

@Component
@Slf4j
public class UserInteractionService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SpotMyStatusProperties spotMyStatusProperties;

    @Autowired
    private LoadingCache<String, CachedUser> userCache;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private SlackPollingClient slackClient;

    @Value("classpath:templates/slack_modal_view_template.json")
    private Resource resourceFile;

    public void invalidateAndNotifyUser(String userId) {
        slackClient.invalidateAndNotifyUser(userId);
    }

    public void handleTrigger(String userId, String triggerId) {
        CachedUser cachedUser = getCachedUser(userId);
        SlackModalView modalViewTemplate = getModalViewTemplate();
        SlackModalIn slackModalIn = new SlackModalIn();
        slackModalIn.setTriggerId(triggerId);
        modalViewTemplate.getBlocks().forEach(block -> {
            if (BLOCK_ID_SPOTIFY_ITEMS.equals(block.getBlockId())) {
                List<Option> defaultItemOptions = spotMyStatusProperties.getDefaultSpotifyItems()
                                                                        .entrySet()
                                                                        .stream()
                                                                        .map(entry -> createOption(entry.getKey(), entry.getValue()))
                                                                        .collect(toList());
                List<Option> selectedItemsOptions = cachedUser.getSpotifyItems()
                                                              .stream()
                                                              .map(spotifyItem -> createOption(spotifyItem,
                                                                  spotMyStatusProperties.getDefaultSpotifyItems().get(spotifyItem)))
                                                              .collect(toList());
                block.getAccessory().setOptions(defaultItemOptions);
                if (selectedItemsOptions.isEmpty()) {
                    block.getAccessory().setInitialOptions(defaultItemOptions);
                } else {
                    block.getAccessory().setInitialOptions(selectedItemsOptions);
                }
            } else if (BLOCK_ID_EMOJI_LIST.equals(block.getBlockId())) {
                List<Option> emojiOptions = getUserEmojis(cachedUser)
                    .stream()
                    .map(emoji -> createOption(emoji, ":" + emoji + ":"))
                    .collect(toList());
                block.getAccessory().setOptions(emojiOptions);
                block.getAccessory().setInitialOptions(emojiOptions);
            } else if (BLOCK_ID_SYNC_TOGGLE.equals(block.getBlockId())) {
                if (getCachedUser(userId).isDisabled()) {
                    block.getAccessory().setInitialOptions(null);
                }
            }
        });
        slackModalIn.setView(modalViewTemplate);
        String response = notifyUser(slackModalIn, "open").getBody();
        log.trace("Received response: {}", response); //todo return summary of changes instead
    }

    private SlackModalView getModalViewTemplate() {
        try {
            return OBJECT_MAPPER.readValue(resourceFile.getInputStream(), SlackModalView.class);
        } catch (IOException e) {
            log.error("Failed to create modal view template", e);
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR);
        }
    }

    private Option createOption(String itemValue, String itemText) {
        Option option = new Option();
        option.setValue(itemValue);
        Text text = new Text();
        text.setType("plain_text");
        text.setText(itemText);
        option.setText(text);
        return option;
    }

    private List<String> getUserEmojis(CachedUser cachedUser) {
        if (cachedUser.getEmojis().isEmpty()) {
            return spotMyStatusProperties.getDefaultEmojis();
        }
        return cachedUser.getEmojis();
    }

    public ResponseEntity<String> notifyUser(Object body, final String viewAction) {
        return RestHelper.builder()
                         .withBaseUrl("https://slack.com/api/views." + viewAction) //todo
                         .withBearer("some_bearer")
                         .withContentType(MediaType.APPLICATION_JSON_UTF8_VALUE)
                         .withBody(body)
                         .post(restTemplate, String.class);
    }

    public void handleUserInteraction(SlackModalIn payload) {
        String userId = getUserId(payload);
        if (PAYLOAD_TYPE_BLOCK_ACTIONS.equals(payload.getType())) {
            String userAction = getUserAction(payload);
            log.debug("User {} triggered {}", userId, userAction);
            if (payload.getActions() != null && ACTION_ID_EMOJI_ADD.equals(userAction)) {
                handleEmojiAdd(payload);
            }
        } else if (PAYLOAD_TYPE_SUBMISSION.equals(payload.getType())) {
            log.debug("User {} submitted the form", userId);
            boolean disableSync = getStateValue(payload, BLOCK_ID_SYNC_TOGGLE).getSelectedOptions().isEmpty();
            List<Option> spotifyItems = getStateValue(payload, BLOCK_ID_SPOTIFY_ITEMS).getSelectedOptions();
            for (Block block : payload.getView().getBlocks()) {
                if (BLOCK_ID_EMOJI_LIST.equals(block.getBlockId())) {
                    StateValue emojiStateValue = getStateValue(payload, BLOCK_ID_EMOJI_LIST);
                    if (isNotBlank(emojiStateValue.getType())) {
                        updateEmojis(userId, emojiStateValue.getSelectedOptions());
                    } else {
                        updateEmojis(userId, block.getAccessory().getInitialOptions());
                    }
                }
            }
            updateSpotifyItems(userId, spotifyItems);
            updateSync(userId, disableSync);
        }
    }

    private void updateSync(String userId, boolean disableSync) {
        if (disableSync) {
            slackClient.pause(userId);
        } else {
            slackClient.resume(userId);
        }
    }

    public void updateEmojis(String userId, List<Option> selectedEmojiOptions) {
        List<String> selectedEmojis = getOptionValues(selectedEmojiOptions);
        if (selectedEmojis.isEmpty()) {
            selectedEmojis.addAll(spotMyStatusProperties.getDefaultEmojis());
        }

        CachedUser cachedUser = getCachedUser(userId);
        cachedUser.setEmojis(selectedEmojis);
        userRepository.findById(userId).ifPresent(user -> {
            user.setEmojis(String.join(",", selectedEmojis));
            userRepository.save(user);
        });
    }

    private CachedUser getCachedUser(String userId) {
        CachedUser cachedUser = userCache.getIfPresent(userId);
        if (cachedUser == null) {
            throw new UserNotFoundException("No user found in cache");
        }
        return cachedUser;
    }

    public void updateSpotifyItems(String userId, List<Option> selectedSpotifyOptions) {
        List<String> spotifyItems = getOptionValues(selectedSpotifyOptions);
        CachedUser cachedUser = getCachedUser(userId);
        cachedUser.setSpotifyItems(spotifyItems);
        userRepository.findById(userId).ifPresent(user -> {
            user.setSpotifyItems(String.join(",", spotifyItems));
            userRepository.save(user);
        });
    }

    private List<String> getOptionValues(List<Option> options) {
        return options.stream().map(Option::getValue).collect(toList());
    }

    private void handleEmojiAdd(SlackModalIn payload) {
        for (Block block : payload.getView().getBlocks()) {
            if (BLOCK_ID_EMOJI_LIST.equals(block.getBlockId())) {
                String newEmojiInput = getStateValue(payload, BLOCK_ID_EMOJI_INPUT).getValue();
                StateValue selectedEmojiBlock = getStateValue(payload, BLOCK_ID_EMOJI_LIST);
                List<Option> selectedOptions;
                if (isBlank(selectedEmojiBlock.getType())) {
                    // if state doesn't change in emoji list block, slack delivers empty block
                    // 'type' field should be present even if user removes all emojis.
                    // That's how we can differentiate actual user input from a no input and set previously set initial options
                    selectedOptions = block.getAccessory().getInitialOptions();
                } else {
                    selectedOptions = selectedEmojiBlock.getSelectedOptions();
                }

                block.getAccessory().setInitialOptions(selectedOptions);
                //todo validate(newEmojiInput)
                if (isBlank(newEmojiInput)) {
                    return;
                }
                Arrays.stream(newEmojiInput.split(","))
                      .filter(StringUtils::isNotBlank)
                      .map(emoji -> emoji.trim().replaceAll(":", ""))
                      .map(emoji -> createOption(emoji, ":" + emoji + ":"))
                      .forEach(emojiOption -> {
                          if (!block.getAccessory().getOptions().contains(emojiOption)) {
                              block.getAccessory().getOptions().add(emojiOption);
                          }
                          if (!block.getAccessory().getInitialOptions().contains(emojiOption)) {
                              block.getAccessory().getInitialOptions().add(emojiOption);
                          }
                      });
                block.getAccessory().setActionId(String.valueOf(System.currentTimeMillis()));
            } else if (BLOCK_ID_EMOJI_INPUT.equals(block.getBlockId())) {
                block.getElement().setActionId(null);
            }
        }
        SlackModalOut slackModal = new SlackModalOut();
        slackModal.setViewId(payload.getView().getId());
        slackModal.setHash(payload.getView().getHash());
        slackModal.setView(payload.getView());
        slackModal.getView().setHash(null);
        slackModal.getView().setId(null);
        slackModal.getView().setState(null);
        String response = notifyUser(slackModal, "update").getBody();//todo
//        log.trace("Received modal update response: {}", response);
    }

    private StateValue getStateValue(SlackModalIn payload, String blockId) {
        return payload.getView()
                      .getState()
                      .getStateValues()
                      .get(blockId);
    }

    private String getUserId(SlackModalIn payload) {
        return payload.getUser() != null ? payload.getUser().getId() : null;
    }

    private String getUserAction(SlackModalIn payload) {
        return Optional.ofNullable(payload)
                       .map(SlackModalIn::getActions)
                       .filter(not(CollectionUtils::isEmpty))
                       .map(actions -> actions.get(0))
                       .map(Action::getActionId)
                       .orElse(null);
    }
}
