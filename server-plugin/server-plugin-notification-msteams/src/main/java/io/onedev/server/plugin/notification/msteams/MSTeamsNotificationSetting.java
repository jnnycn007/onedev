package io.onedev.server.plugin.notification.msteams;

import io.onedev.server.model.support.channelnotification.ChannelNotificationSetting;
import io.onedev.server.annotation.Editable;

@Editable(name="MS Teams Notifications", group="Notification", order=150, description="Set up Microsoft Teams notification " +
        "settings. Settings will be inherited by child projects, and can be overridden by defining settings with " +
        "same webhook url")
public class MSTeamsNotificationSetting extends ChannelNotificationSetting {

    private static final long serialVersionUID = 1L;

} 