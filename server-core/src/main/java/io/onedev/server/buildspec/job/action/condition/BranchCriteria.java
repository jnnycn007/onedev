package io.onedev.server.buildspec.job.action.condition;

import static io.onedev.server.buildspec.job.action.condition.ActionCondition.getRuleName;
import static io.onedev.server.model.Build.NAME_BRANCH;

import javax.annotation.Nullable;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.From;
import javax.persistence.criteria.Predicate;

import io.onedev.commons.utils.match.PathMatcher;
import io.onedev.server.model.Build;
import io.onedev.server.util.ProjectScope;
import io.onedev.server.util.criteria.Criteria;

public class BranchCriteria extends Criteria<Build> {

	private static final long serialVersionUID = 1L;
	
	private final String branch;
	
	private final int operator;
	
	public BranchCriteria(String branch, int operator) {
		this.branch = branch;
		this.operator = operator;
	}
	
	@Override
	public Predicate getPredicate(@Nullable ProjectScope projectScope, CriteriaQuery<?> query, From<Build, Build> from, CriteriaBuilder builder) {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public boolean matches(Build build) {
		var matches = build.getBranch() != null && new PathMatcher().matches(branch, build.getBranch());
		if (operator == ActionConditionLexer.IsNot)
			matches = !matches;
		return matches;
	}

	@Override
	public String toStringWithoutParens() {
		return quote(NAME_BRANCH) + " " + getRuleName(operator) + " " + quote(branch);
	}
	
}
