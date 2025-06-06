package io.onedev.server.web.behavior;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import edu.emory.mathcs.backport.java.util.Collections;
import io.onedev.commons.codeassist.FenceAware;
import io.onedev.commons.codeassist.InputCompletion;
import io.onedev.commons.codeassist.InputSuggestion;
import io.onedev.commons.codeassist.grammar.LexerRuleRefElementSpec;
import io.onedev.commons.codeassist.parser.Element;
import io.onedev.commons.codeassist.parser.ParseExpect;
import io.onedev.commons.codeassist.parser.TerminalExpect;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.BuildParamManager;
import io.onedev.server.model.Build;
import io.onedev.server.model.Project;
import io.onedev.server.search.entity.build.BuildQuery;
import io.onedev.server.search.entity.build.BuildQueryLexer;
import io.onedev.server.search.entity.build.BuildQueryParser;
import io.onedev.server.util.DateUtils;
import io.onedev.server.web.behavior.inputassist.ANTLRAssistBehavior;
import io.onedev.server.web.behavior.inputassist.InputAssistBehavior;
import io.onedev.server.web.util.SuggestionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.wicket.Component;
import org.apache.wicket.model.IModel;

import javax.annotation.Nullable;

import static io.onedev.server.web.translation.Translation._T;

import java.util.ArrayList;
import java.util.List;

public class BuildQueryBehavior extends ANTLRAssistBehavior {

	private static final String FUZZY_SUGGESTION_DESCRIPTION_PREFIX = "enclose with ~";
	
	private final IModel<Project> projectModel;
	
	private final boolean withOrder;
	
	private final boolean withCurrentUserCriteria;
	
	private final boolean withUnfinishedCriteria;
	
	public BuildQueryBehavior(IModel<Project> projectModel, boolean withOrder, boolean withCurrentUserCriteria, 
							  boolean withUnfinishedCriteria) {
		super(BuildQueryParser.class, "query", false);
		this.projectModel = projectModel;
		this.withOrder = withOrder;
		this.withCurrentUserCriteria = withCurrentUserCriteria;
		this.withUnfinishedCriteria = withUnfinishedCriteria;
	}
	
	@Override
	public void detach(Component component) {
		super.detach(component);
		projectModel.detach();
	}
	
	@Nullable
	private Project getProject() {
		return projectModel.getObject();
	}
	
	@Override
	protected List<InputSuggestion> suggest(TerminalExpect terminalExpect) {
		if (terminalExpect.getElementSpec() instanceof LexerRuleRefElementSpec) {
			LexerRuleRefElementSpec spec = (LexerRuleRefElementSpec) terminalExpect.getElementSpec();
			if (spec.getRuleName().equals("Number")) {
				return SuggestionUtils.suggestNumber(
						terminalExpect.getUnmatchedText(), 
						_T("find build with this number"));
			} else if (spec.getRuleName().equals("Quoted")) {
				return new FenceAware(codeAssist.getGrammar(), '"', '"') {

					@Override
					protected List<InputSuggestion> match(String matchWith) {
						Project project = getProject();
						ParseExpect criteriaValueExpect;
						if ("criteriaField".equals(spec.getLabel())) {
							List<String> fields = new ArrayList<>(Build.QUERY_FIELDS);
							if (getProject() != null)
								fields.remove(Build.NAME_PROJECT);
							BuildParamManager buildParamManager = OneDev.getInstance(BuildParamManager.class);
							List<String> paramNames = new ArrayList<>(buildParamManager.getParamNames(project));
							Collections.sort(paramNames);
							fields.addAll(paramNames);
							return SuggestionUtils.suggest(fields, matchWith);
						} else if ("orderField".equals(spec.getLabel())) {
							List<String> candidates = new ArrayList<>(Build.SORT_FIELDS.keySet());
							if (getProject() != null)
								candidates.remove(Build.NAME_PROJECT);
							return SuggestionUtils.suggest(candidates, matchWith);
						} else if ((criteriaValueExpect = terminalExpect.findExpectByLabel("criteriaValue")) != null) {
							List<Element> fieldElements = criteriaValueExpect.getState().findMatchedElementsByLabel("criteriaField", true);
							List<Element> operatorElements = criteriaValueExpect.getState().findMatchedElementsByLabel("operator", true);
							Preconditions.checkState(operatorElements.size() == 1);
							String operatorName = StringUtils.normalizeSpace(operatorElements.get(0).getMatchedText());
							int operator = BuildQuery.getOperator(operatorName);							
							if (fieldElements.isEmpty()) {
								if (operator == BuildQueryLexer.SubmittedBy || operator == BuildQueryLexer.CancelledBy) {
									return SuggestionUtils.suggestUsers(matchWith);
								} else if (operator == BuildQueryLexer.DependsOn || operator == BuildQueryLexer.DependenciesOf) {
									return SuggestionUtils.suggestBuilds(project, matchWith, InputAssistBehavior.MAX_SUGGESTIONS);
								} else if (operator == BuildQueryLexer.FixedIssue) {
									return SuggestionUtils.suggestIssues(project, matchWith, InputAssistBehavior.MAX_SUGGESTIONS);
								} else if (operator == BuildQueryLexer.RanOn) {
									return SuggestionUtils.suggestAgents(matchWith);
								} else { 
									return SuggestionUtils.suggestPullRequests(project, matchWith, InputAssistBehavior.MAX_SUGGESTIONS);
								}
							} else {
								String fieldName = BuildQuery.getValue(fieldElements.get(0).getMatchedText());
 								try {
									BuildQuery.checkField(project, fieldName, operator);
									switch (fieldName) {
										case Build.NAME_SUBMIT_DATE:
										case Build.NAME_PENDING_DATE:
										case Build.NAME_RUNNING_DATE:
										case Build.NAME_FINISH_DATE:
											List<InputSuggestion> suggestions = SuggestionUtils.suggest(DateUtils.RELAX_DATE_EXAMPLES, matchWith);
											return !suggestions.isEmpty() ? suggestions : null;
										case Build.NAME_PROJECT:
											if (!matchWith.contains("*"))
												return SuggestionUtils.suggestProjectPaths(matchWith);
											else
												return null;
										case Build.NAME_JOB:
											if (project != null && !matchWith.contains("*"))
												return SuggestionUtils.suggestJobs(project, matchWith);
											else
												return null;
										case Build.NAME_NUMBER:
											return SuggestionUtils.suggestBuilds(project, matchWith, InputAssistBehavior.MAX_SUGGESTIONS);
										case Build.NAME_VERSION:
											if (project != null && !matchWith.contains("*"))
												return SuggestionUtils.suggestBuildVersions(project, matchWith);
											else
												return null;
										case Build.NAME_BRANCH:
											if (project != null && !matchWith.contains("*"))
												return SuggestionUtils.suggestBranches(project, matchWith);
											else
												return null;
										case Build.NAME_TAG:
											if (project != null && !matchWith.contains("*"))
												return SuggestionUtils.suggestTags(project, matchWith);
											else
												return null;
										case Build.NAME_LABEL:
											return SuggestionUtils.suggestLabels(matchWith);
										case Build.NAME_PULL_REQUEST:
											return SuggestionUtils.suggestPullRequests(project, matchWith, InputAssistBehavior.MAX_SUGGESTIONS);
										default:
											return null;
									}
								} catch (ExplicitException ignored) {
								}
							}
						}
						return new ArrayList<>();
					}
					
					@Override
					protected String getFencingDescription() {
						return _T("value should be quoted");
					}
					
				}.suggest(terminalExpect);
			} else if (spec.getRuleName().equals("Fuzzy")) {
				return new FenceAware(codeAssist.getGrammar(), '~', '~') {

					@Override
					protected List<InputSuggestion> match(String matchWith) {
						return null;
					}

					@Override
					protected String getFencingDescription() {
						return _T("enclose with ~ to query job/version");
					}

				}.suggest(terminalExpect);
			}
		} 
		return null;
	}
	
	@Override
	protected Optional<String> describe(ParseExpect parseExpect, String suggestedLiteral) {
		if (!withOrder && suggestedLiteral.equals(BuildQuery.getRuleName(BuildQueryLexer.OrderBy)))
			return null;
		if (!withCurrentUserCriteria) {
			if (suggestedLiteral.equals(BuildQuery.getRuleName(BuildQueryLexer.SubmittedByMe)) 
					|| suggestedLiteral.equals(BuildQuery.getRuleName(BuildQueryLexer.CancelledByMe))) {
				return null;
			}
		}
		if (!withUnfinishedCriteria) {
			if (suggestedLiteral.equals(BuildQuery.getRuleName(BuildQueryLexer.Running)) 
					|| suggestedLiteral.equals(BuildQuery.getRuleName(BuildQueryLexer.Waiting))
					|| suggestedLiteral.equals(BuildQuery.getRuleName(BuildQueryLexer.Pending))) {
				return null;
			}
		}

		if (suggestedLiteral.equals(",")) {
			if (parseExpect.findExpectByLabel("orderOperator") != null)
				return Optional.of(_T("add another order"));
			else
				return Optional.of(_T("or match another value"));
		} else if (suggestedLiteral.equals("#")) {
			if (getProject() != null)
				return Optional.of(_T("find build by number"));
			else
				return null;
		}
		
		parseExpect = parseExpect.findExpectByLabel("operator");
		if (parseExpect != null) {
			List<Element> fieldElements = parseExpect.getState().findMatchedElementsByLabel("criteriaField", false);
			if (!fieldElements.isEmpty()) {
				String fieldName = BuildQuery.getValue(fieldElements.iterator().next().getMatchedText());
				try {
					BuildQuery.checkField(getProject(), fieldName, BuildQuery.getOperator(suggestedLiteral));
				} catch (ExplicitException e) {
					return null;
				}
			} 
		}
		return super.describe(parseExpect, suggestedLiteral);
	}

	@Override
	protected List<String> getHints(TerminalExpect terminalExpect) {
		List<String> hints = new ArrayList<>();
		if (terminalExpect.getElementSpec() instanceof LexerRuleRefElementSpec) {
			LexerRuleRefElementSpec spec = (LexerRuleRefElementSpec) terminalExpect.getElementSpec();
			if ("criteriaValue".equals(spec.getLabel())) {
				List<Element> fieldElements = terminalExpect.getState().findMatchedElementsByLabel("criteriaField", true);
				if (!fieldElements.isEmpty()) {
					String fieldName = BuildQuery.getValue(fieldElements.get(0).getMatchedText());
					if (fieldName.equals(Build.NAME_PROJECT)) {
						hints.add(_T("Use '**', '*' or '?' for <a href='https://docs.onedev.io/appendix/path-wildcard' target='_blank'>path wildcard match</a>"));
					} else if (fieldName.equals(Build.NAME_VERSION) || fieldName.equals(Build.NAME_JOB)) {
						hints.add(_T("Use '*' for wildcard match"));
						hints.add(_T("Use '\\' to escape quotes"));
					} else if (fieldName.equals(Build.NAME_BRANCH) || fieldName.equals(Build.NAME_TAG)) {
						hints.add(_T("Use '*' for wildcard match"));
					}
				}
			}
		} 
		return hints;
	}

	@Override
	protected boolean isFuzzySuggestion(InputCompletion suggestion) {
		return suggestion.getDescription() != null 
				&& suggestion.getDescription().startsWith(FUZZY_SUGGESTION_DESCRIPTION_PREFIX);
	}
	
}
