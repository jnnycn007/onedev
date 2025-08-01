package io.onedev.server.entitymanager.impl;

import static com.google.common.collect.Lists.newArrayList;
import static edu.emory.mathcs.backport.java.util.Collections.sort;
import static io.onedev.commons.utils.LockUtils.write;
import static io.onedev.server.model.Build.ARTIFACTS_DIR;
import static io.onedev.server.model.Build.LOG_FILE;
import static io.onedev.server.model.Build.PROP_FINISH_DATE;
import static io.onedev.server.model.Build.PROP_FINISH_TIME_GROUPS;
import static io.onedev.server.model.Build.PROP_PENDING_DURATION;
import static io.onedev.server.model.Build.PROP_RUNNING_DURATION;
import static io.onedev.server.model.Build.PROP_STATUS;
import static io.onedev.server.model.Build.SORT_FIELDS;
import static io.onedev.server.model.Build.getArtifactsLockName;
import static io.onedev.server.model.Build.getLogLockName;
import static io.onedev.server.model.Build.getProjectRelativeDirPath;
import static io.onedev.server.model.Build.Status.SUCCESSFUL;
import static io.onedev.server.model.Project.BUILDS_DIR;
import static io.onedev.server.model.Project.SHARE_TEST_DIR;
import static io.onedev.server.search.entity.EntitySort.Direction.ASCENDING;
import static io.onedev.server.util.DirectoryVersionUtils.isVersionFile;
import static java.lang.Long.valueOf;
import static java.util.Arrays.asList;

import java.io.File;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.From;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Selection;
import javax.ws.rs.core.MediaType;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jgit.lib.ObjectId;
import org.hibernate.Session;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.Query;
import org.hibernate.query.criteria.internal.path.SingularAttributePath;
import org.quartz.CronScheduleBuilder;
import org.quartz.ScheduleBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;

import io.onedev.commons.loader.ManagedSerializedForm;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.server.OneDev;
import io.onedev.server.StorageManager;
import io.onedev.server.cluster.ClusterManager;
import io.onedev.server.entitymanager.BuildDependenceManager;
import io.onedev.server.entitymanager.BuildLabelManager;
import io.onedev.server.entitymanager.BuildManager;
import io.onedev.server.entitymanager.BuildParamManager;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.event.Listen;
import io.onedev.server.event.entity.EntityPersisted;
import io.onedev.server.event.entity.EntityRemoved;
import io.onedev.server.event.system.SystemStarting;
import io.onedev.server.event.system.SystemStopping;
import io.onedev.server.git.service.GitService;
import io.onedev.server.model.Agent;
import io.onedev.server.model.Build;
import io.onedev.server.model.Build.Status;
import io.onedev.server.model.BuildDependence;
import io.onedev.server.model.BuildParam;
import io.onedev.server.model.Issue;
import io.onedev.server.model.Project;
import io.onedev.server.model.PullRequest;
import io.onedev.server.model.support.build.BuildPreservation;
import io.onedev.server.persistence.SequenceGenerator;
import io.onedev.server.persistence.SessionManager;
import io.onedev.server.persistence.TransactionManager;
import io.onedev.server.persistence.annotation.Sessional;
import io.onedev.server.persistence.annotation.Transactional;
import io.onedev.server.persistence.dao.BaseEntityManager;
import io.onedev.server.persistence.dao.Dao;
import io.onedev.server.persistence.dao.EntityCriteria;
import io.onedev.server.replica.BuildStorageSyncer;
import io.onedev.server.search.entity.EntityQuery;
import io.onedev.server.search.entity.EntitySort;
import io.onedev.server.search.entity.build.BuildQuery;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.taskschedule.SchedulableTask;
import io.onedev.server.taskschedule.TaskScheduler;
import io.onedev.server.util.ProjectBuildStatusStat;
import io.onedev.server.util.StatusInfo;
import io.onedev.server.util.artifact.ArtifactInfo;
import io.onedev.server.util.artifact.DirectoryInfo;
import io.onedev.server.util.artifact.FileInfo;
import io.onedev.server.util.concurrent.BatchWorkManager;
import io.onedev.server.util.concurrent.BatchWorker;
import io.onedev.server.util.concurrent.Prioritized;
import io.onedev.server.util.criteria.Criteria;
import io.onedev.server.util.facade.BuildFacade;
import io.onedev.server.web.util.StatsGroup;

@Singleton
public class DefaultBuildManager extends BaseEntityManager<Build> implements BuildManager, SchedulableTask, Serializable {

	private static final int STATUS_QUERY_BATCH = 500;
	
	private static final int CLEANUP_BATCH = 5000;

	private static final int HOUSE_KEEPING_PRIORITY = 50;
	
	private static final Logger logger = LoggerFactory.getLogger(DefaultBuildManager.class);
	
	private final BuildParamManager buildParamManager;
	
	private final BuildDependenceManager buildDependenceManager;
	
	private final ProjectManager projectManager;
	
	private final TaskScheduler taskScheduler;
	
	private final SessionManager sessionManager;
	
	private final TransactionManager transactionManager;
	
	private final BuildLabelManager labelManager;
	
	private final ClusterManager clusterManager;
	
	private final StorageManager storageManager;
	
	private final Set<BuildStorageSyncer> storageSyncers;
	
	private final SequenceGenerator numberGenerator;

	private final BatchWorkManager batchWorkManager;
	
	private volatile IMap<Long, BuildFacade> cache;
	
	private volatile IMap<Long, Collection<String>> jobNames;
	
	private volatile String taskId;
	
	@Inject
	public DefaultBuildManager(Dao dao, BuildParamManager buildParamManager, 
							   TaskScheduler taskScheduler, BuildDependenceManager buildDependenceManager, 
							   ProjectManager projectManager, SessionManager sessionManager, 
							   TransactionManager transactionManager, BuildLabelManager labelManager, 
							   ClusterManager clusterManager, StorageManager storageManager, 
							   BatchWorkManager batchWorkManager, Set<BuildStorageSyncer> storageSyncers) {
		super(dao);
		this.buildParamManager = buildParamManager;
		this.buildDependenceManager = buildDependenceManager;
		this.projectManager = projectManager;
		this.taskScheduler = taskScheduler;
		this.sessionManager = sessionManager;
		this.transactionManager = transactionManager;
		this.labelManager = labelManager;
		this.clusterManager = clusterManager;
		this.storageManager = storageManager;
		this.batchWorkManager = batchWorkManager;
		this.storageSyncers = storageSyncers;

		numberGenerator = new SequenceGenerator(Build.class, clusterManager, dao);
	}

	public Object writeReplace() throws ObjectStreamException {
		return new ManagedSerializedForm(BuildManager.class);
	}
	
	@Sessional
	@Override
	public Build find(Project project, long number) {
		EntityCriteria<Build> criteria = newCriteria();
		criteria.add(Restrictions.eq(Build.PROP_NUMBER_SCOPE, project.getForkRoot()));
		criteria.add(Restrictions.eq(Build.PROP_NUMBER, number));
		criteria.setCacheable(true);
		return find(criteria);
	}
	
	@Sessional
	@Override
	public Build findLastFinished(Project project, String jobName, @Nullable String refName) {
		EntityCriteria<Build> criteria = newCriteria();
		criteria.add(Restrictions.eq(Build.PROP_PROJECT, project));
		criteria.add(Restrictions.eq(Build.PROP_JOB_NAME, jobName));
		if (refName != null) {
			refName = refName.replace("*", "%");
			criteria.add(Restrictions.ilike(Build.PROP_REF_NAME, refName));
		}
		criteria.add(Build.Status.ofFinished());
		criteria.addOrder(Order.desc(Build.PROP_NUMBER));
		criteria.setCacheable(true);
		return find(criteria);
	}
	
	@Transactional
	@Override
	public void update(Build build) {
		Preconditions.checkState(!build.isNew());
		dao.persist(build);
	}
	
	@Transactional
	@Listen
	public void on(EntityPersisted event) {
		if (event.getEntity() instanceof Build) {
			Build build = (Build) event.getEntity();
			BuildFacade facade = build.getFacade();
			String jobName = build.getJobName();
			transactionManager.runAfterCommit(() -> {
				cache.put(facade.getId(), facade);
				populateJobNames(facade.getProjectId(), jobName);
			});
		}
	}
	
	@Transactional
	@Listen
	public void on(EntityRemoved event) {
		if (event.getEntity() instanceof Project) {
			Project project = (Project) event.getEntity();
	    	if (project.getForkRoot().equals(project))
	    		numberGenerator.removeNextSequence(project);
			
			Long projectId = project.getId();
			transactionManager.runAfterCommit(() -> {
				cache.removeAll(entry -> entry.getValue().getProjectId().equals(projectId));
				jobNames.remove(projectId);
			});
		} else if (event.getEntity() instanceof Build) {
			Build build = (Build) event.getEntity();
			Long projectId = build.getProject().getId();
			Long buildId = build.getProject().getId();
			Long buildNumber = build.getNumber();

			String activeServer = projectManager.getActiveServer(projectId, false);
			
			transactionManager.runAfterCommit(() -> {
				cache.remove(buildId);
				if (activeServer != null) {
					clusterManager.submitToServer(activeServer, () -> {
						try {
							var buildDir = getBuildDir(projectId, buildNumber);
							FileUtils.deleteDir(buildDir);
							projectManager.directoryModified(projectId, buildDir.getParentFile());
						} catch (Exception e) {
							logger.error("Error deleting storage directory of build id '" + buildId + "'", e);
						}
						return null;
					});

				}
			});
		}
	}

	@Sessional
	@Override
	public Collection<Build> query(Project project, ObjectId commitId) {
		return query(project, commitId, null);
	}
	
	@Sessional
	@Override
	public Collection<Build> query(Project project, ObjectId commitId, String jobName) {
		return query(project, commitId, jobName, null, null, null, new HashMap<>());
	}
	
	@Sessional
	@Override
	public Collection<Build> query(Project project, ObjectId commitId, String jobName, String refName, 
								   Optional<PullRequest> request, Optional<Issue> issue, 
								   Map<String, List<String>> params) {
		CriteriaBuilder builder = getSession().getCriteriaBuilder();
		CriteriaQuery<Build> query = builder.createQuery(Build.class);
		Root<Build> root = query.from(Build.class);
		
		List<Predicate> predicates = new ArrayList<>();
		predicates.add(builder.equal(root.get(Build.PROP_PROJECT), project));
		predicates.add(builder.equal(root.get(Build.PROP_COMMIT_HASH), commitId.name()));
		if (jobName != null)
			predicates.add(builder.equal(root.get(Build.PROP_JOB_NAME), jobName));
		if (refName != null)
			predicates.add(builder.equal(root.get(Build.PROP_REF_NAME), refName));

		if (request != null) {
			if (request.isPresent())
				predicates.add(builder.equal(root.get(Build.PROP_PULL_REQUEST), request.get()));
			else
				predicates.add(builder.isNull(root.get(Build.PROP_PULL_REQUEST)));
		}

		if (issue != null) {
			if (issue.isPresent())
				predicates.add(builder.equal(root.get(Build.PROP_ISSUE), issue.get()));
			else
				predicates.add(builder.isNull(root.get(Build.PROP_ISSUE)));
		}
		
		predicates.addAll(getPredicates(root, builder, params));
		
		query.where(predicates.toArray(new Predicate[0]));
		return getSession().createQuery(query).list();
	}

	@SuppressWarnings("unchecked")
	@Sessional
	@Override
	public Map<Long, Long> queryUnfinished() {
		Query<?> query = getSession().createQuery("select id, project.id from Build where "
				+ "status=:waiting or status=:pending or status=:running");
		query.setParameter("waiting", Build.Status.WAITING);
		query.setParameter("pending", Build.Status.PENDING);
		query.setParameter("running", Build.Status.RUNNING);
		
		Map<Long, Long> result = new HashMap<>();
		for (Object[] fields: (List<Object[]>)query.list()) 
			result.put((Long)fields[0], (Long)fields[1]);
		return result;
	}

	@Sessional
	@Override
	public Collection<Build> queryUnfinished(Project project, String jobName, @Nullable String refName, 
											 @Nullable Optional<PullRequest> request, @Nullable Optional<Issue> issue, 
											 Map<String, List<String>> params) {
		CriteriaBuilder builder = getSession().getCriteriaBuilder();
		CriteriaQuery<Build> query = builder.createQuery(Build.class);
		Root<Build> root = query.from(Build.class);
		
		List<Predicate> predicates = new ArrayList<>();
		predicates.add(builder.or(
				builder.equal(root.get(Build.PROP_STATUS), Build.Status.PENDING),
				builder.equal(root.get(Build.PROP_STATUS), Build.Status.RUNNING),
				builder.equal(root.get(Build.PROP_STATUS), Build.Status.WAITING)));
		predicates.add(builder.equal(root.get(Build.PROP_PROJECT), project));
		predicates.add(builder.equal(root.get(Build.PROP_JOB_NAME), jobName));
		
		if (refName != null)
			predicates.add(builder.equal(root.get(Build.PROP_REF_NAME), refName));

		if (request != null) {
			if (request.isPresent())
				predicates.add(builder.equal(root.get(Build.PROP_PULL_REQUEST), request.get()));
			else
				predicates.add(builder.isNull(root.get(Build.PROP_PULL_REQUEST)));
		}
		if (issue != null) {
			if (issue.isPresent())
				predicates.add(builder.equal(root.get(Build.PROP_ISSUE), issue.get()));
			else
				predicates.add(builder.isNull(root.get(Build.PROP_ISSUE)));
		}
			
		predicates.addAll(getPredicates(root, builder, params));
		
		query.where(predicates.toArray(new Predicate[0]));
		return getSession().createQuery(query).list();
	}
	
	private List<Predicate> getPredicates(From<Build, Build> root, CriteriaBuilder builder, 
			Map<String, List<String>> params) {
		List<Predicate> predicates = new ArrayList<>();
		
		for (Map.Entry<String, List<String>> entry: params.entrySet()) {
			if (!entry.getValue().isEmpty()) {
				for (String value: entry.getValue()) {
					Join<?, ?> join = root.join(Build.PROP_PARAMS, JoinType.INNER);
					predicates.add(builder.equal(join.get(BuildParam.PROP_NAME), entry.getKey()));
					predicates.add(builder.equal(join.get(BuildParam.PROP_VALUE), value));
				}
			} else {
				Join<?, ?> join = root.join(Build.PROP_PARAMS, JoinType.INNER);
				predicates.add(builder.equal(join.get(BuildParam.PROP_NAME), entry.getKey()));
				predicates.add(builder.isNull(join.get(BuildParam.PROP_VALUE)));
			}
		}
		
		return predicates;
	}
	
	@Sessional
	@Override
	public List<Build> query(Project project, String fuzzyQuery, int count) {
		List<Build> builds = new ArrayList<>();

		EntityCriteria<Build> criteria = newCriteria();

		List<Criterion> projectCriterions = new ArrayList<>();
		Collection<String> availableJobNames = jobNames.get(project.getId());
		if (availableJobNames != null && !availableJobNames.isEmpty()) {
			Collection<String> accessibleJobNames = getAccessibleJobNames(project);
			if (accessibleJobNames.containsAll(availableJobNames)) {
				projectCriterions.add(Restrictions.eq(Build.PROP_PROJECT, project));
			} else {
				List<Criterion> jobCriterions = new ArrayList<>();
				for (String jobName: accessibleJobNames) 
					jobCriterions.add(Restrictions.eq(Build.PROP_JOB_NAME, jobName));
				if (!jobCriterions.isEmpty()) {
					projectCriterions.add(Restrictions.and(
							Restrictions.eq(Build.PROP_PROJECT, project), 
							Restrictions.or(jobCriterions.toArray(new Criterion[0]))));
				}
			}
		}
		
		if (!projectCriterions.isEmpty()) {
			criteria.add(Restrictions.or(projectCriterions.toArray(new Criterion[0])));
			
			if (fuzzyQuery.length() != 0) {
				try {
					long buildNumber = Long.parseLong(fuzzyQuery);
					criteria.add(Restrictions.eq(Build.PROP_NUMBER, buildNumber));
				} catch (NumberFormatException e) {
					criteria.add(Restrictions.or(
							Restrictions.ilike(Build.PROP_VERSION, fuzzyQuery, MatchMode.ANYWHERE),
							Restrictions.ilike(Build.PROP_JOB_NAME, fuzzyQuery, MatchMode.ANYWHERE)));
				}
			}

			criteria.addOrder(Order.desc(Build.PROP_PROJECT));
			criteria.addOrder(Order.desc(Build.PROP_NUMBER));
			builds.addAll(query(criteria, 0, count));
		} 

		return builds;
	}
	
	@Sessional
	@Override
	public List<String> queryVersions(Project project, String matchWith, int count) {
		CriteriaBuilder builder = getSession().getCriteriaBuilder();
		CriteriaQuery<String> criteriaQuery = builder.createQuery(String.class);
		Root<Build> root = criteriaQuery.from(Build.class);
		criteriaQuery.select(root.get(Build.PROP_VERSION)).distinct(true);
		
		Collection<Predicate> predicates = getPredicates(project, root, builder);
		predicates.add(builder.like(
				builder.lower(root.get(Build.PROP_VERSION)), 
				"%" + matchWith.toLowerCase() + "%"));
		criteriaQuery.where(predicates.toArray(new Predicate[0]));
		criteriaQuery.orderBy(builder.asc(root.get(Build.PROP_VERSION)));

		Query<String> query = getSession().createQuery(criteriaQuery);
		query.setFirstResult(0);
		query.setMaxResults(count);
		
		return query.getResultList();
	}

	@Sessional
	@Override
	public Map<Integer, Pair<Integer, Integer>> queryDurationStats(Project project, 
				@Nullable Criteria<Build> buildCriteria, @Nullable Date startDate, 
				@Nullable Date endDate, StatsGroup group) {
		CriteriaBuilder builder = dao.getSession().getCriteriaBuilder();
		CriteriaQuery<Object[]> criteriaQuery = builder.createQuery(Object[].class);
		Root<Build> root = criteriaQuery.from(Build.class);
		
		var predicates = new ArrayList<Predicate>();
		predicates.addAll(asList(getPredicates(project, buildCriteria, criteriaQuery, root, builder)));
		predicates.add(builder.equal(root.get(PROP_STATUS), SUCCESSFUL));
		predicates.add(builder.isNotNull(root.get(PROP_PENDING_DURATION)));
		predicates.add(builder.isNotNull(root.get(PROP_RUNNING_DURATION)));
		if (startDate != null)
			predicates.add(builder.greaterThanOrEqualTo(root.get(PROP_FINISH_DATE), startDate));
		if (endDate != null)
			predicates.add(builder.lessThanOrEqualTo(root.get(PROP_FINISH_DATE), endDate));
		criteriaQuery.where(predicates.toArray(new Predicate[0]));
		var groupPath = group.getPath(root.get(PROP_FINISH_TIME_GROUPS));
		criteriaQuery.groupBy(groupPath);

		criteriaQuery.multiselect(newArrayList(
				groupPath,
				builder.avg(root.get(PROP_PENDING_DURATION)),
				builder.avg(root.get(PROP_RUNNING_DURATION))));

		Map<Integer, Pair<Integer, Integer>> stats = new HashMap<>();
		for (var result: getSession().createQuery(criteriaQuery).getResultList()) {
			var pendingDuration = ((Double)result[1])/60000;
			var runningDuration = ((Double)result[2])/60000;
			stats.put((int)result[0], new ImmutablePair<>((int)pendingDuration, (int)runningDuration));
		}
		return stats;
	}

	@Sessional
	@Override
	public Map<Integer, Integer> queryFrequencyStats(Project project, 
				@Nullable Criteria<Build> buildCriteria, @Nullable Date startDate, 
				@Nullable Date endDate, StatsGroup group) {
		CriteriaBuilder builder = dao.getSession().getCriteriaBuilder();
		CriteriaQuery<Object[]> criteriaQuery = builder.createQuery(Object[].class);
		Root<Build> root = criteriaQuery.from(Build.class);

		var predicates = new ArrayList<Predicate>(asList(getPredicates(project, buildCriteria, criteriaQuery, root, builder)));
		predicates.add(builder.isNotNull(root.get(PROP_FINISH_DATE)));
		if (startDate != null)
			predicates.add(builder.greaterThanOrEqualTo(root.get(PROP_FINISH_DATE), startDate));
		if (endDate != null)
			predicates.add(builder.lessThanOrEqualTo(root.get(PROP_FINISH_DATE), endDate));
		criteriaQuery.where(predicates.toArray(new Predicate[0]));
		var groupPath = group.getPath(root.get(PROP_FINISH_TIME_GROUPS));
		criteriaQuery.groupBy(groupPath);

		criteriaQuery.multiselect(newArrayList(groupPath, builder.count(root)));
		
		Map<Integer, Integer> stats = new HashMap<>();
		for (var result: getSession().createQuery(criteriaQuery).getResultList()) 
			stats.put((int)result[0], ((Long)result[1]).intValue());
		return stats;
	}
	
	@Transactional
	@Override
	public void create(Build build) {
		Preconditions.checkArgument(build.isNew());
		build.setNumberScope(build.getProject().getForkRoot());
		build.setNumber(numberGenerator.getNextSequence(build.getNumberScope()));
		
		dao.persist(build);
		
		for (BuildParam param: build.getParams())
			buildParamManager.create(param);
		for (BuildDependence dependence: build.getDependencies())
			buildDependenceManager.create(dependence);
	}

	private Collection<Predicate> getPredicates(@Nullable Project project, From<Build, Build> root, CriteriaBuilder builder) {
		Collection<Predicate> predicates = new ArrayList<>();

		if (project != null) {
			predicates.add(builder.equal(root.get(Build.PROP_PROJECT), project));
			if (!SecurityUtils.canManageBuilds(project)) {
				Collection<String> accessibleJobNames = getAccessibleJobNames(project);
				Collection<String> availableJobNames = jobNames.get(project.getId());
				if (availableJobNames != null && !accessibleJobNames.containsAll(availableJobNames)) {
					List<Predicate> jobPredicates = new ArrayList<>();
					for (String jobName: accessibleJobNames) 
						jobPredicates.add(builder.equal(root.get(Build.PROP_JOB_NAME), jobName));
					predicates.add(builder.or(jobPredicates.toArray(new Predicate[0])));
				}
			}
		} else if (!SecurityUtils.isAdministrator()) {
			List<Predicate> projectPredicates = new ArrayList<>();
			Collection<Long> projectsWithAllJobs = new HashSet<>();
			for (Map.Entry<Project, Collection<String>> entry: getAccessibleJobNames().entrySet()) {
				project = entry.getKey();
				if (SecurityUtils.canManageBuilds(project)) {
					projectPredicates.add(builder.equal(root.get(Build.PROP_PROJECT), project));
					projectsWithAllJobs.add(project.getId());
				} else {
					Collection<String> availableJobNamesOfProject = jobNames.get(project.getId());
					if (availableJobNamesOfProject != null) {
						Collection<String> accessibleJobNamesOfProject = entry.getValue();
						if (accessibleJobNamesOfProject.containsAll(availableJobNamesOfProject)) {
							projectsWithAllJobs.add(project.getId());
							projectPredicates.add(builder.equal(root.get(Build.PROP_PROJECT), project));
						} else {
							List<Predicate> jobPredicates = new ArrayList<>();
							for (String jobName: accessibleJobNamesOfProject) 
								jobPredicates.add(builder.equal(root.get(Build.PROP_JOB_NAME), jobName));
							projectPredicates.add(builder.and(
									builder.equal(root.get(Build.PROP_PROJECT), project), 
									builder.or(jobPredicates.toArray(new Predicate[0]))));
						}
					} else {
						projectsWithAllJobs.add(project.getId());
					}
				}
			}
			if (!projectsWithAllJobs.containsAll(jobNames.keySet()))
				predicates.add(builder.or(projectPredicates.toArray(new Predicate[0])));
		}
		
		return predicates;
	}
	
	private Predicate[] getPredicates(@Nullable Project project, @Nullable Criteria<Build> criteria, 
			CriteriaQuery<?> query, From<Build, Build> root, CriteriaBuilder builder) {
		Collection<Predicate> predicates = getPredicates(project, root, builder);
		if (criteria != null) 
			predicates.add(criteria.getPredicate(null, query, root, builder));
		return predicates.toArray(new Predicate[0]);
	}
	
	private CriteriaQuery<Build> buildCriteriaQuery(@Nullable Project project, 
			Session session,  EntityQuery<Build> buildQuery) {
		CriteriaBuilder builder = session.getCriteriaBuilder();
		CriteriaQuery<Build> query = builder.createQuery(Build.class);
		Root<Build> root = query.from(Build.class);
		query.select(root);
		
		query.where(getPredicates(project, buildQuery.getCriteria(), query, root, builder));

		applyOrders(root, query, builder, buildQuery);
		
		return query;
	}
	
	@Sessional
	@Override
	public List<Build> query(@Nullable Project project, EntityQuery<Build> buildQuery, 
			boolean loadLabels, int firstResult, int maxResults) {
		CriteriaQuery<Build> criteriaQuery = buildCriteriaQuery(project, getSession(), buildQuery);
		Query<Build> query = getSession().createQuery(criteriaQuery);
		query.setFirstResult(firstResult);
		query.setMaxResults(maxResults);
		var builds = query.getResultList();
		if (!builds.isEmpty() && loadLabels) 
			labelManager.populateLabels(builds);
		return builds;		
	}

	@SuppressWarnings("rawtypes")
	private void applyOrders(From<Build, Build> root, CriteriaQuery<?> criteriaQuery, CriteriaBuilder builder, 
			EntityQuery<Build> buildQuery) {
		List<javax.persistence.criteria.Order> orders = new ArrayList<>();
		for (EntitySort sort: buildQuery.getSorts()) {
			if (sort.getDirection() == ASCENDING)
				orders.add(builder.asc(BuildQuery.getPath(root, SORT_FIELDS.get(sort.getField()).getProperty())));
			else
				orders.add(builder.desc(BuildQuery.getPath(root, SORT_FIELDS.get(sort.getField()).getProperty())));
		}

		boolean found = false;
		for (var order: orders) {
			if (order.getExpression() instanceof SingularAttributePath 
					&& ((SingularAttributePath) order.getExpression()).getAttribute().getName().equals(Build.PROP_SUBMIT_DATE)) {
				found = true;
				break;
			}
		}
		if (!found)
			orders.add(builder.desc(root.get(Build.PROP_SUBMIT_DATE)));

		criteriaQuery.orderBy(orders);
	}
	
	@Sessional
	@Override
	public Collection<Long> queryIds(Project project, EntityQuery<Build> buildQuery, 
			int firstResult, int maxResults) {
		CriteriaBuilder builder = getSession().getCriteriaBuilder();
		CriteriaQuery<Long> criteriaQuery = builder.createQuery(Long.class);
		Root<Build> root = criteriaQuery.from(Build.class);
		criteriaQuery.select(root.get(Build.PROP_ID));

		criteriaQuery.where(getPredicates(project, buildQuery.getCriteria(), criteriaQuery, root, builder));

		applyOrders(root, criteriaQuery, builder, buildQuery);

		Query<Long> query = getSession().createQuery(criteriaQuery);
		query.setFirstResult(firstResult);
		query.setMaxResults(maxResults);
		
		return query.list();
	}
	
	@Sessional
	@Override
	public int count(@Nullable Project project, Criteria<Build> buildCriteria) {
		CriteriaBuilder builder = getSession().getCriteriaBuilder();
		CriteriaQuery<Long> criteriaQuery = builder.createQuery(Long.class);
		Root<Build> root = criteriaQuery.from(Build.class);

		criteriaQuery.where(getPredicates(project, buildCriteria, criteriaQuery, root, builder));

		criteriaQuery.select(builder.count(root));
		return getSession().createQuery(criteriaQuery).uniqueResult().intValue();
	}
	
	@Sessional
	@Override
	public Map<ObjectId, Map<String, Collection<StatusInfo>>> queryStatus(Project project, Collection<ObjectId> commitIds) {
		Map<ObjectId, Map<String, Collection<StatusInfo>>> commitStatuses = new HashMap<>();
		
		Collection<ObjectId> batch = new HashSet<>();
		for (ObjectId commitId: commitIds) {
			batch.add(commitId);
			if (batch.size() == STATUS_QUERY_BATCH) {
				fillStatus(project, batch, commitStatuses);
				batch.clear();
			}
		}
		if (!batch.isEmpty())
			fillStatus(project, batch, commitStatuses);
		for (ObjectId commitId: commitIds) {
			if (!commitStatuses.containsKey(commitId))
				commitStatuses.put(commitId, new HashMap<>());
		}
		return commitStatuses;
	}
	
	@SuppressWarnings("unchecked")
	private void fillStatus(Project project, Collection<ObjectId> commitIds, 
			Map<ObjectId, Map<String, Collection<StatusInfo>>> commitStatuses) {
		Query<?> query = getSession().createQuery("select commitHash, jobName, status, refName, request.id from Build "
				+ "where project=:project and commitHash in :commitHashes");
		query.setParameter("project", project);
		query.setParameter("commitHashes", commitIds.stream().map(it->it.name()).collect(Collectors.toList()));
		for (Object[] row: (List<Object[]>)query.list()) {
			ObjectId commitId = ObjectId.fromString((String) row[0]);
			String jobName = (String) row[1];
			Status status = (Status) row[2];
			String refName = (String) row[3];
			Long requestId = (Long) row[4];
			
			Map<String, Collection<StatusInfo>> commitStatus = commitStatuses.get(commitId);
			if (commitStatus == null) {
				commitStatus = new HashMap<>();
				commitStatuses.put(commitId, commitStatus);
			}
			Collection<StatusInfo> jobStatus = commitStatus.get(jobName);
			if (jobStatus == null) {
				jobStatus = new HashSet<>();
				commitStatus.put(jobName, jobStatus);
			}
			jobStatus.add(new StatusInfo(status, requestId, refName));
		}
	}
	
	@Sessional
	protected long getMaxId() {
		CriteriaBuilder builder = getSession().getCriteriaBuilder();
		CriteriaQuery<Long> query = builder.createQuery(Long.class);
		Root<Build> root = query.from(Build.class);
		query.select(builder.max(root.get(Build.PROP_ID)));
		Long maxId = getSession().createQuery(query).getSingleResult();
		return maxId!=null?maxId:0;
	}

	@Override
	public void execute() {
		batchWorkManager.submit(new BatchWorker("build-manager-house-keeping") {

			@Override
			public void doWorks(List<Prioritized> works) {
				if (clusterManager.isLeaderServer()) {
					long maxId = getMaxId();
					Collection<Long> idsToPreserve = new HashSet<>();
					
					sessionManager.run(() -> {
						for (Project project: projectManager.query()) {
							logger.debug("Populating preserved build ids of project '" + project.getPath() + "'...");
							List<BuildPreservation> preservations = project.getHierarchyBuildPreservations();
							if (preservations.isEmpty()) {
								idsToPreserve.addAll(queryIds(project, new BuildQuery(), 0, Integer.MAX_VALUE));
							} else {
								for (BuildPreservation preservation: preservations) {
									try {
										BuildQuery query = BuildQuery.parse(project, preservation.getCondition(), false, false);
										int count;
										if (preservation.getCount() != null)
											count = preservation.getCount();
										else
											count = Integer.MAX_VALUE;
										idsToPreserve.addAll(queryIds(project, query, 0, count));
									} catch (Exception e) {
										String message = String.format("Error parsing build preserve condition(project: %s, condition: %s)", 
												project.getPath(), preservation.getCondition());
										logger.error(message, e);
										idsToPreserve.addAll(queryIds(project, new BuildQuery(), 0, Integer.MAX_VALUE));
									}
								}
							}
						}
					});
		
					EntityCriteria<Build> criteria = newCriteria();
					AtomicInteger firstResult = new AtomicInteger(0);
					
					while (transactionManager.call(() -> {
						List<Build> builds = query(criteria, firstResult.get(), CLEANUP_BATCH);
						if (!builds.isEmpty()) {
							logger.debug("Checking build preservation: {}->{}", 
									firstResult.get()+1, firstResult.get()+builds.size());
						}
						for (Build build: builds) {
							if (build.isFinished() && build.getId() <= maxId && !idsToPreserve.contains(build.getId())) {
								logger.debug("Deleting " + build.getReference() + "...");
								delete(build);
							}
						}
						firstResult.set(firstResult.get() + CLEANUP_BATCH);
						return builds.size() == CLEANUP_BATCH;
					})) {}			
				}		
			}

		}, new Prioritized(HOUSE_KEEPING_PRIORITY));		
	}

	@Override
	public ScheduleBuilder<?> getScheduleBuilder() {
		return CronScheduleBuilder.dailyAtHourAndMinute(0, 0);
	}
	
	@SuppressWarnings("unchecked")
	@Listen
	public void on(SystemStarting event) {
		logger.info("Caching build info...");
		
		HazelcastInstance hazelcastInstance = clusterManager.getHazelcastInstance();
        cache = hazelcastInstance.getMap("buildCache");
        jobNames = hazelcastInstance.getMap("jobNames");

		var buildCacheInited = hazelcastInstance.getCPSubsystem().getAtomicLong("buildCacheInited");
		clusterManager.initWithLead(buildCacheInited, () -> {
			Query<?> query = dao.getSession().createQuery("select id, project.id, number, commitHash, jobName from Build");
			for (Object[] fields : (List<Object[]>) query.list()) {
				Long buildId = (Long) fields[0];
				Long projectId = (Long) fields[1];
				Long buildNumber = (Long) fields[2];
				cache.put(buildId, new BuildFacade(buildId, projectId, buildNumber, (String) fields[3]));
				populateJobNames(projectId, (String) fields[4]);
			}
			return 1L;			
		});
		
		taskId = taskScheduler.schedule(this);
	}

	@Listen
	public void on(SystemStopping event) {
		if (taskId != null)
			taskScheduler.unschedule(taskId);
	}
	
	private CriteriaQuery<Object[]> buildStreamPreviousQuery(Build build, Status status, String...fields) {
		CriteriaBuilder builder = getSession().getCriteriaBuilder();
		CriteriaQuery<Object[]> query = builder.createQuery(Object[].class);
		Root<Build> root = query.from(Build.class);
		
		List<Predicate> predicates = new ArrayList<>();
		predicates.add(builder.equal(root.get("project"), build.getProject()));
		predicates.add(builder.equal(root.get("jobName"), build.getJobName()));
		if (status != null)
			predicates.add(builder.equal(root.get("status"), status));
		predicates.add(builder.lessThan(root.get("number"), build.getNumber()));
		query.where(predicates.toArray(new Predicate[0]));
		List<Selection<?>> selections = new ArrayList<>();
		for (String field: fields)
			selections.add(root.get(field));
		query.multiselect(selections);
		
		return query;
	}
	
	@Sessional
	@Override
	public Collection<Long> queryStreamPreviousNumbers(Build build, Status status, int limit) {
		Map<ObjectId, Long> buildNumbers = new HashMap<>();
		CriteriaQuery<Object[]> query = buildStreamPreviousQuery(build, status, "commitHash", "number", "refName");
		var result = getSession().createQuery(query).list();
		for (Object[] fields: result) {
			if (build.getRefName().equals(fields[2]))
				buildNumbers.put(ObjectId.fromString((String) fields[0]), (Long)fields[1]);
		}
		
		if (buildNumbers.isEmpty()) {
			for (Object[] fields: result) 
				buildNumbers.put(ObjectId.fromString((String) fields[0]), (Long)fields[1]);
		}
		
		if (!buildNumbers.isEmpty()) {
			return getGitService().filterParents(build.getProject(), build.getCommitId(), 
					buildNumbers, limit);
		} else { 
			return new HashSet<>();
		}
	}
	
	private GitService getGitService() {
		return OneDev.getInstance(GitService.class);
	}

	@Sessional
	@Override
	public Build findStreamPrevious(Build build, Status status) {
		Map<ObjectId, Long> buildIds = new HashMap<>();
		var result = getSession().createQuery(buildStreamPreviousQuery(
				build, status, "commitHash", "id", "refName")).list();
		for (Object[] fields: result) {
			if (build.getRefName().equals(fields[2]))
				buildIds.put(ObjectId.fromString((String) fields[0]), (Long)fields[1]);
		}
		if (buildIds.isEmpty()) {
			for (Object[] fields: result) 
				buildIds.put(ObjectId.fromString((String) fields[0]), (Long)fields[1]);
		}
		
		if (!buildIds.isEmpty()) {
			Collection<Long> filtered = getGitService().filterParents(
					build.getProject(), build.getCommitId(), buildIds, 1);
			if (!filtered.isEmpty())
				return load(filtered.iterator().next());
		}
		return null;
	}
	
	@Override
	public Collection<Long> getNumbers(Long projectId) {
		return cache.project(
				input -> input.getValue().getNumber(), 
				mapEntry -> mapEntry.getValue().getProjectId().equals(projectId));
	}

	@Override
	public Collection<Long> filterNumbers(Long projectId, Collection<String> commitHashes) {
		return cache.project(
				input -> input.getValue().getNumber(),
				mapEntry -> {
					var build = mapEntry.getValue();
					return build.getProjectId().equals(projectId) 
							&& commitHashes.contains(build.getCommitHash());					
				});
	}
	
	private void populateJobNames(Long projectId, String jobName) {		
		jobNames.compute(projectId, (key, existingJobNames) -> {
			Collection<String> jobNamesOfProject = existingJobNames != null ? existingJobNames : new HashSet<>();
			jobNamesOfProject.add(jobName);
			return jobNamesOfProject;
		});
	}

	@Override
	public Collection<String> getJobNames(@Nullable Project project) {
		Collection<String> jobNames = new HashSet<>();
		for (Map.Entry<Long, Collection<String>> entry: this.jobNames.entrySet()) { 
			if (project == null || project.getId().equals(entry.getKey()))
				jobNames.addAll(entry.getValue());
		}
		return jobNames;
	}
	
	@Override
	public Map<Project, Collection<String>> getAccessibleJobNames() {
		Map<Project, Collection<String>> accessibleJobNames = new HashMap<>();
		for (Long projectId: jobNames.keySet()) {
			Project project = projectManager.load(projectId);
			Collection<String> accessibleJobNamsOfProject = getAccessibleJobNames(project);
			if (!accessibleJobNamsOfProject.isEmpty())
				accessibleJobNames.put(project, accessibleJobNamsOfProject); 
		}
		return accessibleJobNames;
	}
	
	@Override
	public Collection<String> getAccessibleJobNames(Project project) {
		Collection<String> availableJobNames = jobNames.get(project.getId());
		if (availableJobNames != null) 
			return SecurityUtils.getAccessibleJobNames(project, availableJobNames);
		else 
			return new HashSet<>();
	}

	@Override
	public void populateBuilds(Collection<PullRequest> requests) {
		CriteriaBuilder builder = getSession().getCriteriaBuilder();
		CriteriaQuery<Build> query = builder.createQuery(Build.class);
		
		Root<Build> root = query.from(Build.class);
		query.select(root);
		root.join(Build.PROP_PULL_REQUEST);
		
		query.where(root.get(Build.PROP_PULL_REQUEST).in(requests));
		
		for (PullRequest request: requests)
			request.setBuilds(new ArrayList<>());
		
		for (Build build: getSession().createQuery(query).getResultList())
			build.getRequest().getBuilds().add(build);
	}

	@Transactional
	@Override
	public void delete(Collection<Build> builds) {
		for (Build build: builds)
			delete(build);
	}

	@Sessional
	@Override
	public Collection<Build> query(Agent agent, Status status) {
		EntityCriteria<Build> criteria = EntityCriteria.of(Build.class);
		EntityCriteria<Build> agentCriteria = criteria.createCriteria(Build.PROP_AGENT, org.hibernate.sql.JoinType.INNER_JOIN);
		if (status != null) 
			agentCriteria.add(Restrictions.eq(Build.PROP_STATUS, status));
		return query(criteria);
	}

	@Sessional
	@Override
	public List<ProjectBuildStatusStat> queryStatusStats(Collection<Project> projects) {
		if (projects.isEmpty()) {
			return new ArrayList<>();
		} else {
			CriteriaBuilder builder = getSession().getCriteriaBuilder();
			CriteriaQuery<ProjectBuildStatusStat> criteriaQuery = builder.createQuery(ProjectBuildStatusStat.class);
			Root<Build> root = criteriaQuery.from(Build.class);
			criteriaQuery.multiselect(
					root.get(Build.PROP_PROJECT).get(Project.PROP_ID), 
					root.get(Build.PROP_STATUS), 
					builder.count(root));
			criteriaQuery.groupBy(root.get(Build.PROP_PROJECT), root.get(Build.PROP_STATUS));
			
			criteriaQuery.where(root.get(Build.PROP_PROJECT).in(projects));
			criteriaQuery.orderBy(builder.asc(root.get(Build.PROP_STATUS)));
			
			return getSession().createQuery(criteriaQuery).getResultList();
		}
	}

	@Override
	public Build find(String uuid) {
		EntityCriteria<Build> criteria = newCriteria();
		criteria.add(Restrictions.eq(Issue.PROP_UUID, uuid));
		criteria.setCacheable(true);
		return find(criteria);
	}
	
	@Override
	@Nullable
	public ArtifactInfo getArtifactInfo(Build build, @Nullable String artifactPath) {
		Long projectId = build.getProject().getId();
		Long buildNumber = build.getNumber();
		return projectManager.runOnActiveServer(projectId, () -> {
			File artifactsDir = getArtifactsDir(projectId, buildNumber);
			File artifactFile;
			if (artifactPath != null)
				artifactFile = new File(artifactsDir, artifactPath);
			else
				artifactFile = artifactsDir;

			if (artifactFile.exists()) {
				if (artifactFile.isFile()) {
					String mediaType = Files.probeContentType(artifactFile.toPath());
					if (mediaType == null)
						mediaType = MediaType.APPLICATION_OCTET_STREAM;
					return new FileInfo(artifactPath, artifactFile.lastModified(),
							artifactFile.length(), mediaType);
				} else {
					List<ArtifactInfo> children = new ArrayList<>();
					int baseLen = artifactsDir.getAbsolutePath().length() + 1;
					for (File child : artifactFile.listFiles()) {
						if (!isVersionFile(child) && !child.getName().equals(SHARE_TEST_DIR)) {
							var relativePath = child.getAbsolutePath().substring(baseLen);
							if (child.isFile()) {
								children.add(new FileInfo(relativePath, child.lastModified(),
										child.length(), null));
							} else {
								children.add(new DirectoryInfo(relativePath, child.lastModified(), null));
							}
						}
					}
					sort(children, (Comparator<ArtifactInfo>) (o1, o2) -> {
						if (o1 instanceof FileInfo && o2 instanceof FileInfo
								|| (o1 instanceof DirectoryInfo) && (o2 instanceof DirectoryInfo)) {
							return o1.getPath().compareTo(o2.getPath());
						} else if (o1 instanceof FileInfo) {
							return 1;
						} else {
							return -1;
						}
					});
					return new DirectoryInfo(artifactPath, artifactFile.lastModified(), children);
				}
			} else {
				return null;
			}
		});
	}
	
	@Override
	public void deleteArtifact(Build build, @Nullable String artifactPath) {
		Long projectId = build.getProject().getId();
		Long buildNumber = build.getNumber();
		projectManager.runOnActiveServer(projectId, () -> write(getArtifactsLockName(projectId, buildNumber), () -> {
			File artifactsDir = getArtifactsDir(projectId, buildNumber);
			if (artifactPath != null) {
				File artifactFile = new File(artifactsDir, artifactPath);
				if (artifactFile.exists()) {
					if (artifactFile.isFile())
						FileUtils.deleteFile(artifactFile);
					else 
						FileUtils.deleteDir(artifactsDir);
				} else {
					String errorMessage = String.format(
							"Unable to find specified artifact (project: %s, build number: %d, artifact path: %s)",
							projectId, buildNumber, artifactPath);
					throw new ExplicitException(errorMessage);
				}
			} else {
				FileUtils.cleanDir(artifactsDir);
			}
			projectManager.directoryModified(projectId, artifactsDir);
			return null;
		}));
	}

	@Override
	public void syncBuilds(Long projectId, String activeServer) {
		projectManager.syncDirectory(projectId, BUILDS_DIR, (suffix) -> {
			projectManager.syncDirectory(projectId, BUILDS_DIR + "/" + suffix, (buildNumberString) -> {
				var buildNumber = valueOf(buildNumberString);
				var buildPath = getProjectRelativeDirPath(buildNumber);
				projectManager.syncFile(projectId, buildPath + "/" + LOG_FILE, 
						getLogLockName(projectId, buildNumber), activeServer);
				if (clusterManager.runOnServer(activeServer, () -> getArtifactsDir(projectId, buildNumber).exists())) {
					var artifactsDir = storageManager.initArtifactsDir(projectId, buildNumber);
					var artifactsPath = Build.getProjectRelativeDirPath(buildNumber) + "/" + ARTIFACTS_DIR;
					if (!projectManager.isSharedDir(artifactsDir, activeServer, projectId, artifactsPath)) {
						projectManager.syncDirectory(projectId, buildPath + "/" + ARTIFACTS_DIR,
								getArtifactsLockName(projectId, buildNumber), activeServer);
					}
				}
				storageSyncers.forEach(it->it.sync(projectId, buildNumber, activeServer));
			}, activeServer);
		}, activeServer);
	}

	@Override
	public File getBuildDir(Long projectId, Long buildNumber) {
		return projectManager.getSubDir(projectId, Build.getProjectRelativeDirPath(buildNumber));
	}

	@Override
	public File getArtifactsDir(Long projectId, Long buildNumber) {
		return new File(getBuildDir(projectId, buildNumber), ARTIFACTS_DIR);
	}

}
