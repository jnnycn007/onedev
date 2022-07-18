package io.onedev.server.model.support.administration.mailsetting;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.validator.constraints.NotEmpty;

import com.google.common.collect.Lists;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.server.mail.MailCheckSetting;
import io.onedev.server.mail.MailCredential;
import io.onedev.server.mail.MailSendSetting;
import io.onedev.server.mail.OAuthAccessToken;
import io.onedev.server.util.EditContext;
import io.onedev.server.web.editable.annotation.Editable;
import io.onedev.server.web.editable.annotation.Password;
import io.onedev.server.web.editable.annotation.RefreshToken;

@Editable(order=100, name="Office 365")
public class Office365Setting extends MailSetting {

	private static final long serialVersionUID = 1L;
	
	private String tenantId;
	
	private String clientId;
	
	private String clientSecret;
	
	private String userPrincipalName;
	
	private String refreshToken;
	
	private String emailAddress;
	
	private InboxPollSetting inboxPollSetting;
	
	@Editable(order=50, description="Specify tenant ID to perform OAuth authentcation against")
	@NotEmpty
	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	@Editable(order=100, description="Client ID (or application ID) of this OneDev instance registered in Azure AD")
	@NotEmpty
	public String getClientId() {
		return clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	@Editable(order=200, description="Client secret of this OneDev instance registered in Azure AD")
	@Password
	@NotEmpty
	public String getClientSecret() {
		return clientSecret;
	}

	public void setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
	}

	@Editable(order=300, description="Principal name of the account to login into office 365 mail server to "
			+ "send/receive emails")
	@NotEmpty
	public String getUserPrincipalName() {
		return userPrincipalName;
	}

	public void setUserPrincipalName(String userPrincipalName) {
		this.userPrincipalName = userPrincipalName;
	}

	@Editable(order=400, description="Long-live refresh token of above account which will be used to generate access token "
			+ "to access office 365 mail server. <b class='text-info'>TIPS: </b> you may use the button at right "
			+ "side of this field to generate refresh token. Note that whenever tenant id, client id, client secret, or "
			+ "user principal name is changed, refresh token should be re-generated")
	@RefreshToken("getRefreshTokenCallback")
	@NotEmpty
	public String getRefreshToken() {
		return refreshToken;
	}

	public void setRefreshToken(String refreshToken) {
		this.refreshToken = refreshToken;
	}
	
	@Editable(order=410, name="System Email Address", description="This email address will be used as sender "
			+ "address for various notifications. Its inbox will also be checked if <code>Check Incoming Email</code> "
			+ "option is enabled below")
	@NotEmpty
	public String getEmailAddress() {
		return emailAddress;
	}

	public void setEmailAddress(String emailAddress) {
		this.emailAddress = emailAddress;
	}
	
	private static String getTokenEndpoint(String tenantId) {
		return String.format("https://login.microsoftonline.com/%s/oauth2/v2.0/token", tenantId);
	}

	@SuppressWarnings("unused")
	private static RefreshToken.Callback getRefreshTokenCallback() {
		String tenantId = (String) EditContext.get().getInputValue("tenantId");
		if (tenantId == null)
			throw new ExplicitException("Tenant ID needs to be specified to generate refresh token");
		String clientId = (String) EditContext.get().getInputValue("clientId");
		if (clientId == null)
			throw new ExplicitException("Client ID needs to be specified to generate refresh token");
		String clientSecret = (String) EditContext.get().getInputValue("clientSecret");
		if (clientSecret == null)
			throw new ExplicitException("Client secret needs to be specified to generate refresh token");
		
		String userPrincipalName = (String) EditContext.get().getInputValue("userPrincipalName");
		if (userPrincipalName == null)
			throw new ExplicitException("User principal name needs to be specified to generate refresh token");

		Collection<String> scopes = Lists.newArrayList(
				"https://outlook.office.com/SMTP.Send", 
				"https://outlook.office.com/IMAP.AccessAsUser.All", 
				"offline_access");
		
		String authorizeEndpoint = String.format(
				"https://login.microsoftonline.com/%s/oauth2/v2.0/authorize", tenantId);
		String tokenEndpoint = getTokenEndpoint(tenantId);
		
		return new RefreshToken.Callback() {

			@Override
			public String getAuthorizeEndpoint() {
				return authorizeEndpoint;
			}

			@Override
			public Map<String, String> getAuthorizeParams() {
				Map<String, String> params = new HashMap<>();
				params.put("login_hint", userPrincipalName);
				params.put("prompt", "consent");
				return params;
			}
			
			@Override
			public String getClientId() {
				return clientId;
			}

			@Override
			public String getClientSecret() {
				return clientSecret;
			}

			@Override
			public String getTokenEndpoint() {
				return tokenEndpoint;
			}

			@Override
			public Collection<String> getScopes() {
				return scopes;
			}

		};
	}
	
	@Editable(order=500, name="Check Incoming Email")
	public InboxPollSetting getInboxPollSetting() {
		return inboxPollSetting;
	}

	public void setInboxPollSetting(InboxPollSetting inboxPollSetting) {
		this.inboxPollSetting = inboxPollSetting;
	}

	@Override
	public MailSendSetting getSendSetting() {
		MailCredential smtpCredential = new OAuthAccessToken(
				getTokenEndpoint(tenantId), clientId, clientSecret, refreshToken);
		return new MailSendSetting("smtp.office365.com", 587, userPrincipalName, smtpCredential, 
				emailAddress, true, getTimeout());
	}

	@Override
	public MailCheckSetting getCheckSetting() {
		if (inboxPollSetting != null) {
			String imapUser = getUserPrincipalName();
			MailCredential imapCredential = new OAuthAccessToken(
					getTokenEndpoint(tenantId), clientId, clientSecret, refreshToken);
			
			return new MailCheckSetting("outlook.office365.com", 993, 
					imapUser, imapCredential, emailAddress, true, 
					inboxPollSetting.getPollInterval(), getTimeout());
		} else {
			return null;
		}
	}

}