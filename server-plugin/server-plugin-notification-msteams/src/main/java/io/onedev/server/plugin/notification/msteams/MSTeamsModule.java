package io.onedev.server.plugin.notification.msteams;

import java.util.List;

import com.beust.jcommander.internal.Lists;

import io.onedev.commons.loader.AbstractPluginModule;
import io.onedev.server.web.page.project.setting.ContributedProjectSetting;
import io.onedev.server.web.page.project.setting.ProjectSettingContribution;

/**
 * NOTE: Do not forget to rename moduleClass property defined in the pom if you've renamed this class.
 */
public class MSTeamsModule extends AbstractPluginModule {

    @Override
    protected void configure() {
        super.configure();
        
        contribute(ProjectSettingContribution.class, new ProjectSettingContribution() {
            
            @SuppressWarnings("unchecked")
            @Override
            public List<Class<? extends ContributedProjectSetting>> getSettingClasses() {
                return Lists.newArrayList(MSTeamsNotificationSetting.class);
            }
            
        });
        bind(MSTeamsNotificationManager.class);
    }
} 