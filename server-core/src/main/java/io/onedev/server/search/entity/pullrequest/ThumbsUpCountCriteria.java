package io.onedev.server.search.entity.pullrequest;

import javax.annotation.Nullable;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.From;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;

import io.onedev.server.model.PullRequest;
import io.onedev.server.util.ProjectScope;
import io.onedev.server.util.criteria.Criteria;

public class ThumbsUpCountCriteria extends Criteria<PullRequest> {

	private static final long serialVersionUID = 1L;

	private final int operator;
	
	private final int value;
	
	public ThumbsUpCountCriteria(int value, int operator) {
		this.operator = operator;
		this.value = value;
	}

	@Override
	public Predicate getPredicate(@Nullable ProjectScope projectScope, CriteriaQuery<?> query, From<PullRequest, PullRequest> from, CriteriaBuilder builder) {
		Path<Integer> attribute = from.get(PullRequest.PROP_THUMBS_UP_COUNT);
		if (operator == PullRequestQueryLexer.Is)
			return builder.equal(attribute, value);
		else if (operator == PullRequestQueryLexer.IsNot)
			return builder.not(builder.equal(attribute, value));
		else if (operator == PullRequestQueryLexer.IsGreaterThan)
			return builder.greaterThan(attribute, value);
		else
			return builder.lessThan(attribute, value);
	}

	@Override
	public boolean matches(PullRequest request) {
		if (operator == PullRequestQueryLexer.Is)
			return request.getThumbsUpCount() == value;
		else if (operator == PullRequestQueryLexer.IsNot)
			return request.getThumbsUpCount() != value;
		else if (operator == PullRequestQueryLexer.IsGreaterThan)
			return request.getThumbsUpCount() > value;
		else
			return request.getThumbsUpCount() < value;
	}

	@Override
	public String toStringWithoutParens() {
		return quote(PullRequest.NAME_THUMBS_UP_COUNT) + " " 
				+ PullRequestQuery.getRuleName(operator) + " " 
				+ quote(String.valueOf(value));
	}

} 