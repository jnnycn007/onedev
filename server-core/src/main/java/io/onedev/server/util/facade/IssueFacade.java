package io.onedev.server.util.facade;

public class IssueFacade extends EntityFacade {
	
	private static final long serialVersionUID = 1L;
	
	private final Long projectId;

	private final Long number;
	
	public IssueFacade(Long id, Long projectId, Long number) {
		super(id);
		this.projectId = projectId;
		this.number = number;
	}

	public Long getProjectId() {
		return projectId;
	}

	public Long getNumber() {
		return number;
	}

}