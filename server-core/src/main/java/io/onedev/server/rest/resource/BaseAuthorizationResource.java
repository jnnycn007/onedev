package io.onedev.server.rest.resource;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.shiro.authz.UnauthorizedException;

import io.onedev.server.entitymanager.BaseAuthorizationManager;
import io.onedev.server.model.BaseAuthorization;
import io.onedev.server.rest.annotation.Api;
import io.onedev.server.security.SecurityUtils;

@Api(order=7500, description = "This resource manages default roles of project")
@Path("/base-authorizations")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Singleton
public class BaseAuthorizationResource {

	private final BaseAuthorizationManager authorizationManager;

	@Inject
	public BaseAuthorizationResource(BaseAuthorizationManager authorizationManager) {
		this.authorizationManager = authorizationManager;
	}

	@Api(order=100, description = "Get base authorization of specified id")
	@Path("/{authorizationId}")
	@GET
	public BaseAuthorization get(@PathParam("authorizationId") Long authorizationId) {
		var authorization = authorizationManager.load(authorizationId);
		if (!SecurityUtils.canManageProject(authorization.getProject()))
			throw new UnauthorizedException();
		return authorization;
	}
	
	@Api(order=200, description="Create base authorization")
	@POST
	public Long create(@NotNull BaseAuthorization authorization) {
		if (!SecurityUtils.canManageProject(authorization.getProject()))
			throw new UnauthorizedException();
		authorizationManager.create(authorization);
		return authorization.getId();
	}

	@Api(order=300, description = "Delete base authorization of specified id")
	@Path("/{authorizationId}")
	@DELETE
	public Response delete(@PathParam("authorizationId") Long authorizationId) {
		var authorization = authorizationManager.load(authorizationId);
		if (!SecurityUtils.canManageProject(authorization.getProject()))
			throw new UnauthorizedException();
		authorizationManager.delete(authorization);
		return Response.ok().build();
	}
	
}
