package io.onedev.server.plugin.notification.msteams;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.formatter.NodeFormattingHandler;

import io.onedev.server.event.project.ProjectEvent;
import io.onedev.server.markdown.ExternalImageFormatter;
import io.onedev.server.markdown.ExternalLinkFormatter;
import io.onedev.server.markdown.MarkdownManager;
import io.onedev.server.notification.ActivityDetail;
import io.onedev.server.notification.ChannelNotificationManager;
import io.onedev.server.util.CollectionUtils;
import io.onedev.server.util.commenttext.CommentText;
import io.onedev.server.util.commenttext.MarkdownText;
import io.onedev.server.util.commenttext.PlainText;

@Singleton
public class MSTeamsNotificationManager extends ChannelNotificationManager<MSTeamsNotificationSetting> {
    
    private final ObjectMapper objectMapper;
    
    private final MarkdownManager markdownManager;
    
    @Inject
    public MSTeamsNotificationManager(ObjectMapper objectMapper, MarkdownManager markdownManager) {
        this.objectMapper = objectMapper;
        this.markdownManager = markdownManager;
    }

    @Override
    protected void post(HttpPost post, String title, ProjectEvent event) {
        List<Object> sections = new ArrayList<>();
        
        sections.add(CollectionUtils.newHashMap(
                "activityTitle", title));
        
        ActivityDetail activityDetail = event.getActivityDetail();
        if (activityDetail != null) {
            sections.add(CollectionUtils.newHashMap(
                    "text", activityDetail.getTextVersion()));
        }
        
        CommentText commentText = event.getCommentText();
        if (commentText instanceof MarkdownText) {
            String markdown = commentText.getPlainContent();
            
            Set<NodeFormattingHandler<?>> handlers = new HashSet<>();
            handlers.add(new NodeFormattingHandler<>(Link.class, new ExternalLinkFormatter<>()));
            handlers.add(new NodeFormattingHandler<>(Image.class, new ExternalImageFormatter<>()));
            
            sections.add(CollectionUtils.newHashMap(
                    "text", markdownManager.format(markdown, handlers)));
        } else if (commentText instanceof PlainText) {
            sections.add(CollectionUtils.newHashMap(
                    "text", commentText.getPlainContent()));
        }
        
        sections.add(CollectionUtils.newHashMap(
                "potentialAction", List.of(CollectionUtils.newHashMap(
                        "@type", "OpenUri",
                        "name", "View in OneDev",
                        "targets", List.of(CollectionUtils.newHashMap(
                                "os", "default",
                                "uri", event.getUrl()
                        ))
                ))));

        try {
            var json = objectMapper.writeValueAsString(CollectionUtils.newHashMap(
                    "@type", "MessageCard",
                    "@context", "http://schema.org/extensions",
                    "summary", title,
                    "themeColor", "0076D7",
                    "sections", sections));
            post.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
} 