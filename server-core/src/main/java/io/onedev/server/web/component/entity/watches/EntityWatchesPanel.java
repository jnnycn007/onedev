package io.onedev.server.web.component.entity.watches;

import static io.onedev.server.web.translation.Translation._T;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.markup.head.CssHeaderItem;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.LoadableDetachableModel;

import io.onedev.server.model.AbstractEntity;
import io.onedev.server.model.User;
import io.onedev.server.model.support.EntityWatch;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.util.watch.WatchStatus;
import io.onedev.server.web.component.user.ident.Mode;
import io.onedev.server.web.component.user.ident.UserIdentPanel;
import io.onedev.server.web.component.user.list.SimpleUserListLink;
import io.onedev.server.web.component.watchstatus.WatchStatusLink;

public abstract class EntityWatchesPanel extends Panel {

	private static final int MAX_DISPLAY_AVATARS = 20;
	
	public EntityWatchesPanel(String id) {
		super(id);
	}

	private List<EntityWatch> getEffectWatches() {
		List<EntityWatch> watches = new ArrayList<>();
		for (EntityWatch watch: getEntity().getWatches()) {
			if (watch.isWatching() && isAuthorized(watch.getUser()))
				watches.add(watch);
		}
		watches.sort((o1, o2) -> o2.getId().compareTo(o1.getId()));
		return watches;
	}
	
	protected abstract boolean isAuthorized(User user);
	
	@Override
	protected void onInitialize() {
		super.onInitialize();

		add(new Label("title", new LoadableDetachableModel<String>() {

			@Override
			protected String load() {
				return _T("Watchers") + " (" + getEffectWatches().size() + ")";
			}
			
		}));
		
		add(new ListView<>("watchers", new LoadableDetachableModel<List<EntityWatch>>() {

			@Override
			protected List<EntityWatch> load() {
				List<EntityWatch> watches = getEffectWatches();
				if (watches.size() > MAX_DISPLAY_AVATARS)
					watches = watches.subList(0, MAX_DISPLAY_AVATARS);
				return watches;
			}

		}) {

			@Override
			protected void populateItem(ListItem<EntityWatch> item) {
				User user = item.getModelObject().getUser();
				item.add(new UserIdentPanel("watcher", user, Mode.AVATAR));
			}

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(!getEffectWatches().isEmpty());
			}

		});
		
		add(new SimpleUserListLink("more") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(getEffectWatches().size() > MAX_DISPLAY_AVATARS);
			}

			@Override
			protected List<User> getUsers() {
				List<EntityWatch> watches = getEffectWatches();
				if (watches.size() > MAX_DISPLAY_AVATARS)
					watches = watches.subList(MAX_DISPLAY_AVATARS, watches.size());
				else
					watches = new ArrayList<>();
				return watches.stream().map(it->it.getUser()).collect(Collectors.toList());
			}
			
		});
		
		add(new WatchStatusLink("watch") {

			@Override
			protected void onConfigure() {
				super.onConfigure();
				setVisible(SecurityUtils.getAuthUser() != null && !SecurityUtils.getAuthUser().isServiceAccount());
			}

			@Override
			protected WatchStatus getWatchStatus() {
				EntityWatch watch = getEntity().getWatch(SecurityUtils.getAuthUser(), false);
				if (watch != null && !watch.isWatching())
					return WatchStatus.IGNORE;
				else if (watch != null && watch.isWatching())
					return WatchStatus.WATCH;
				else
					return WatchStatus.DEFAULT;
			}

			@Override
			protected void onWatchStatusChange(AjaxRequestTarget target, WatchStatus watchStatus) {
				if (watchStatus == WatchStatus.IGNORE) {
					EntityWatch watch = getEntity().getWatch(SecurityUtils.getAuthUser(), true);
					watch.setWatching(false);
					onSaveWatch(watch);
				} else if (watchStatus == WatchStatus.WATCH) {
					EntityWatch watch = getEntity().getWatch(SecurityUtils.getAuthUser(), true);
					watch.setWatching(true);
					onSaveWatch(watch);
				} else {
					EntityWatch watch = getEntity().getWatch(SecurityUtils.getAuthUser(), false);
					if (watch != null) {
						getEntity().getWatches().remove(watch);
						onDeleteWatch(watch);
					}
				}
				target.add(EntityWatchesPanel.this);
			}
			
		});
		
		setOutputMarkupId(true);
	}

	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);
		response.render(CssHeaderItem.forReference(new EntityWatchesCssResourceReference()));
	}

	protected abstract AbstractEntity getEntity();
	
	protected abstract void onSaveWatch(EntityWatch watch);
	
	protected abstract void onDeleteWatch(EntityWatch watch);
	
}
