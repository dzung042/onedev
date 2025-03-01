package io.onedev.server.entitymanager.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.From;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.UnauthorizedException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.quartz.ScheduleBuilder;
import org.quartz.SimpleScheduleBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.Sets;

import io.onedev.commons.loader.Listen;
import io.onedev.commons.loader.ListenerRegistry;
import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.server.buildspec.job.JobManager;
import io.onedev.server.entitymanager.BuildManager;
import io.onedev.server.entitymanager.IssueManager;
import io.onedev.server.entitymanager.LinkSpecManager;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.entitymanager.RoleManager;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.entitymanager.UserAuthorizationManager;
import io.onedev.server.event.ProjectCreated;
import io.onedev.server.event.ProjectEvent;
import io.onedev.server.event.RefUpdated;
import io.onedev.server.event.entity.EntityPersisted;
import io.onedev.server.event.entity.EntityRemoved;
import io.onedev.server.event.system.SystemStarted;
import io.onedev.server.event.system.SystemStopping;
import io.onedev.server.git.GitUtils;
import io.onedev.server.git.RefInfo;
import io.onedev.server.git.command.CloneCommand;
import io.onedev.server.infomanager.CommitInfoManager;
import io.onedev.server.model.Build;
import io.onedev.server.model.Group;
import io.onedev.server.model.GroupAuthorization;
import io.onedev.server.model.Issue;
import io.onedev.server.model.LinkSpec;
import io.onedev.server.model.Milestone;
import io.onedev.server.model.Project;
import io.onedev.server.model.PullRequest;
import io.onedev.server.model.Role;
import io.onedev.server.model.User;
import io.onedev.server.model.UserAuthorization;
import io.onedev.server.model.support.BranchProtection;
import io.onedev.server.model.support.TagProtection;
import io.onedev.server.model.support.administration.GlobalProjectSetting;
import io.onedev.server.persistence.SessionManager;
import io.onedev.server.persistence.TransactionManager;
import io.onedev.server.persistence.annotation.Sessional;
import io.onedev.server.persistence.annotation.Transactional;
import io.onedev.server.persistence.dao.BaseEntityManager;
import io.onedev.server.persistence.dao.Dao;
import io.onedev.server.search.entity.EntityQuery;
import io.onedev.server.search.entity.EntitySort;
import io.onedev.server.search.entity.EntitySort.Direction;
import io.onedev.server.search.entity.issue.IssueQueryUpdater;
import io.onedev.server.search.entity.project.ProjectQuery;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.security.permission.AccessProject;
import io.onedev.server.util.criteria.Criteria;
import io.onedev.server.util.facade.ProjectCache;
import io.onedev.server.util.facade.ProjectFacade;
import io.onedev.server.util.patternset.PatternSet;
import io.onedev.server.util.schedule.SchedulableTask;
import io.onedev.server.util.schedule.TaskScheduler;
import io.onedev.server.util.usage.Usage;
import io.onedev.server.web.avatar.AvatarManager;

@Singleton
public class DefaultProjectManager extends BaseEntityManager<Project> 
		implements ProjectManager, SchedulableTask {

	private static final Logger logger = LoggerFactory.getLogger(DefaultProjectManager.class);
	
    private final CommitInfoManager commitInfoManager;
    
    private final BuildManager buildManager;
    
    private final AvatarManager avatarManager;
    
    private final SettingManager settingManager;
    
    private final SessionManager sessionManager;
    
    private final TransactionManager transactionManager;
    
    private final IssueManager issueManager;
    
    private final LinkSpecManager linkSpecManager;
    
    private final JobManager jobManager;
    
    private final TaskScheduler taskScheduler;
    
    private final ListenerRegistry listenerRegistry;
    
    private final RoleManager roleManager;
    
    private final UserAuthorizationManager userAuthorizationManager;
    
    private final String gitReceiveHook;
    
	private final Map<Long, Repository> repositoryCache = new ConcurrentHashMap<>();
	
	private final Map<Long, Date> updateDates = new ConcurrentHashMap<>();
	
	private final ProjectCache cache = new ProjectCache();
	
	private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
	
	private String taskId;
	
    @Inject
    public DefaultProjectManager(Dao dao, CommitInfoManager commitInfoManager,  
    		BuildManager buildManager, AvatarManager avatarManager, 
    		SettingManager settingManager, TransactionManager transactionManager, 
    		SessionManager sessionManager, ListenerRegistry listenerRegistry, 
    		TaskScheduler taskScheduler, UserAuthorizationManager userAuthorizationManager, 
    		RoleManager roleManager, JobManager jobManager, IssueManager issueManager, 
    		LinkSpecManager linkSpecManager) {
    	super(dao);
    	
        this.commitInfoManager = commitInfoManager;
        this.buildManager = buildManager;
        this.avatarManager = avatarManager;
        this.settingManager = settingManager;
        this.transactionManager = transactionManager;
        this.sessionManager = sessionManager;
        this.listenerRegistry = listenerRegistry;
        this.taskScheduler = taskScheduler;
        this.userAuthorizationManager = userAuthorizationManager;
        this.roleManager = roleManager;
        this.jobManager = jobManager;
        this.issueManager = issueManager;
        this.linkSpecManager = linkSpecManager;
        
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("git-receive-hook")) {
        	Preconditions.checkNotNull(is);
            gitReceiveHook = StringUtils.join(IOUtils.readLines(is, Charset.defaultCharset()), "\n");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
    
    @Override
    public Repository getRepository(Project project) {
    	Repository repository = repositoryCache.get(project.getId());
    	if (repository == null) {
    		synchronized (repositoryCache) {
    			repository = repositoryCache.get(project.getId());
    			if (repository == null) {
    				try {
						repository = new FileRepository(project.getGitDir());
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
    				repositoryCache.put(project.getId(), repository);
    			}
    		}
    	}
    	return repository;
    }
    
    @Transactional
    @Override
    public void save(Project project) {
    	String oldPath = project.getPath();
		String newPath = project.calcPath();
		if (!newPath.equals(oldPath)) {
			project.setPath(newPath);
			for (Project descendant: project.getDescendants()) {
				descendant.setPath(descendant.calcPath());
				dao.persist(descendant);
			}
		}
    	dao.persist(project);
    	if (oldPath != null && !oldPath.equals(project.getPath())) {
    		Collection<Milestone> milestones = new ArrayList<>();
    		for (Milestone milestone: issueManager.queryUsedMilestones(project)) {
    			if (!project.isSelfOrAncestorOf(milestone.getProject()) 
    					&& !milestone.getProject().isSelfOrAncestorOf(project)) {
    				milestones.add(milestone);
    			}
    		}
    		issueManager.clearSchedules(project, milestones);
    		settingManager.onMoveProject(oldPath, project.getPath());
    		
    		for (LinkSpec link: linkSpecManager.query()) {
    			for (IssueQueryUpdater updater: link.getQueryUpdaters())
    				updater.onMoveProject(oldPath, project.getPath());
    		}
    			
    		scheduleTree(project);
    	}
    }
    
    private void scheduleTree(Project project) {
    	jobManager.schedule(project);
    	for (Project child: project.getChildren()) 
    		scheduleTree(child);
    }
    
    @Transactional
    @Override
    public void create(Project project) {
    	Project parent = project.getParent();
    	if (parent != null && parent.isNew())
    		create(parent);
    	project.setPath(project.calcPath());
    	dao.persist(project);
       	checkSanity(project);
       	UserAuthorization authorization = new UserAuthorization();
       	authorization.setProject(project);
       	authorization.setUser(SecurityUtils.getUser());
       	authorization.setRole(roleManager.getOwner());
       	userAuthorizationManager.save(authorization);
       	listenerRegistry.post(new ProjectCreated(project));
    }
    
    @Transactional
    @Listen
    public void on(EntityRemoved event) {
    	if (event.getEntity() instanceof Project) {
    		Project project = (Project) event.getEntity();
    		Long projectId = project.getId();
    		transactionManager.runAfterCommit(new Runnable() {

				@Override
				public void run() {
					cacheLock.writeLock().lock();
					try {
						cache.remove(projectId);
					} finally {
						cacheLock.writeLock().unlock();
					}
				}
    			
    		});
    	}
    }
    
    @Transactional
    @Listen
    public void on(EntityPersisted event) {
    	if (event.getEntity() instanceof Project) {
    		ProjectFacade facade = ((Project)event.getEntity()).getFacade();
    		transactionManager.runAfterCommit(new Runnable() {

				@Override
				public void run() {
					cacheLock.writeLock().lock();
					try {
						cache.put(facade.getId(), facade);
					} finally {
						cacheLock.writeLock().unlock();
					}
				}
    			
    		});
    	}
    }
    
    @Transactional
    @Override
    public void delete(Project project) {
    	for (Project child: project.getChildren())
    		delete(child);
    	
    	Usage usage = new Usage();
    	usage.add(settingManager.onDeleteProject(project.getPath()));
    	
		for (LinkSpec link: linkSpecManager.query()) {
			for (IssueQueryUpdater updater: link.getQueryUpdaters())
				usage.add(updater.onDeleteProject(project.getPath()).prefix("issue setting").prefix("administration"));
		}
    	
    	usage.checkInUse("Project '" + project.getPath() + "'");

    	for (Project fork: project.getForks()) {
    		Collection<Project> descendants = fork.getForkChildren();
    		descendants.add(fork);
    		for (Project descendant: descendants) {
            	Query<?> query = getSession().createQuery(String.format("update Issue set %s=:fork where %s=:descendant", 
            			Issue.PROP_NUMBER_SCOPE, Issue.PROP_PROJECT));
            	query.setParameter("fork", fork);
            	query.setParameter("descendant", descendant);
            	query.executeUpdate();
            	
            	query = getSession().createQuery(String.format("update Build set %s=:fork where %s=:descendant", 
            			Build.PROP_NUMBER_SCOPE, Build.PROP_PROJECT));
            	query.setParameter("fork", fork);
            	query.setParameter("descendant", descendant);
            	query.executeUpdate();
            	
            	query = getSession().createQuery(String.format("update PullRequest set %s=:fork where %s=:descendant", 
            			PullRequest.PROP_NUMBER_SCOPE, PullRequest.PROP_TARGET_PROJECT));
            	query.setParameter("fork", fork);
            	query.setParameter("descendant", descendant);
            	query.executeUpdate();
    		}
    	}
    	
    	Query<?> query = getSession().createQuery(String.format("update Project set %s=null where %s=:forkedFrom", 
    			Project.PROP_FORKED_FROM, Project.PROP_FORKED_FROM));
    	query.setParameter("forkedFrom", project);
    	query.executeUpdate();

    	query = getSession().createQuery(String.format("update PullRequest set %s=null where %s=:sourceProject", 
    			PullRequest.PROP_SOURCE_PROJECT, PullRequest.PROP_SOURCE_PROJECT));
    	query.setParameter("sourceProject", project);
    	query.executeUpdate();

    	for (Build build: project.getBuilds()) 
    		buildManager.delete(build);
    	
    	dao.remove(project);
    	
    	synchronized (repositoryCache) {
			Repository repository = repositoryCache.remove(project.getId());
			if (repository != null) 
				repository.close();
		}
    }
    
    @Override
    public Project findByPath(String path) {
		cacheLock.readLock().lock();
		try {
			Long projectId = cache.findId(path);
			if (projectId != null)
				return load(projectId);
			else
				return null;
		} finally {
			cacheLock.readLock().unlock();
		}
    }
    
    @Sessional
    @Override
    public Project findByServiceDeskName(String serviceDeskName) {
		cacheLock.readLock().lock();
		try {
			Long projectId = null;
			for (ProjectFacade facade: cache.values()) {
				if (serviceDeskName.equals(facade.getServiceDeskName())) {
					projectId = facade.getId();
					break;
				}
			}
			if (projectId != null)
				return load(projectId);
			else
				return null;
		} finally {
			cacheLock.readLock().unlock();
		}
    }
    
    @Sessional
    @Override
    public Project initialize(String path) {
    	List<String> names = Splitter.on("/").omitEmptyStrings().trimResults().splitToList(path);
    	Project project = null;
    	for (String name: names) { 
    		Project child;
    		if (project == null || !project.isNew()) {
    			child = find(project, name);
    			if (child == null) {
	    			if (project == null && !SecurityUtils.canCreateRootProjects())
	    				throw new UnauthorizedException("Not authorized to create root project");
	    			if (project != null && !SecurityUtils.canCreateChildren(project))
	    				throw new UnauthorizedException("Not authorized to create project under '" + project.getPath() + "'");
	    			child = new Project();
	    			child.setName(name);
	    			child.setParent(project); 
    			}
    		} else {
    			child = new Project();
    			child.setName(name);
    			child.setParent(project);
    		}
    		project = child;
    	}
    	
    	Project parent = project.getParent();
    	while (parent != null && parent.isNew()) {
    		parent.setCodeManagement(false);
    		parent.setIssueManagement(false);
    		parent = parent.getParent();
    	}
    	
    	return project;
    }
    
    @Sessional
    @Override
    public Project find(Project parent, String name) {
		cacheLock.readLock().lock();
		try {
			Long projectId = null;
			for (ProjectFacade facade: cache.values()) {
				if (facade.getName().equalsIgnoreCase(name) 
						&& Objects.equals(Project.idOf(parent), facade.getParentId())) {
					projectId = facade.getId();
					break;
				}
			}
			if (projectId != null)
				return load(projectId);
			else
				return null;
		} finally {
			cacheLock.readLock().unlock();
		}
    }
    
    @Transactional
	@Override
	public void fork(Project from, Project to) {
    	Project parent = to.getParent();
    	if (parent != null && parent.isNew())
    		create(parent);
    	
    	dao.persist(to);
    	
       	UserAuthorization authorization = new UserAuthorization();
       	authorization.setProject(to);
       	authorization.setUser(SecurityUtils.getUser());
       	authorization.setRole(roleManager.getOwner());
       	userAuthorizationManager.save(authorization);
    	
        FileUtils.cleanDir(to.getGitDir());
        new CloneCommand(to.getGitDir()).mirror(true).from(from.getGitDir().getAbsolutePath()).call();
        checkSanity(to);
        commitInfoManager.cloneInfo(from, to);
        avatarManager.copyAvatar(from, to);
        
        if (from.getLfsObjectsDir().exists()) {
            for (File file: FileUtils.listFiles(from.getLfsObjectsDir(), Sets.newHashSet("**"), Sets.newHashSet())) {
            	String objectId = file.getName();
            	Lock lock = from.getLfsObjectLock(objectId).readLock();
            	lock.lock();
            	try {
            		FileUtils.copyFile(file, to.getLfsObjectFile(objectId));
            	} catch (IOException e) {
            		throw new RuntimeException(e);
    			} finally {
            		lock.unlock();
            	}
            }
        }
        
        listenerRegistry.post(new ProjectCreated(to));
	}
    
    @Transactional
    @Override
    public void clone(Project project, String repositoryUrl) {
    	Project parent = project.getParent();
    	if (parent != null && parent.isNew())
    		create(parent);
    	
    	dao.persist(project);
    	
    	User user = SecurityUtils.getUser();
       	UserAuthorization authorization = new UserAuthorization();
       	authorization.setProject(project);
       	authorization.setUser(user);
       	authorization.setRole(roleManager.getOwner());
       	project.getUserAuthorizations().add(authorization);
       	user.getProjectAuthorizations().add(authorization);
       	userAuthorizationManager.save(authorization);
    	
        FileUtils.cleanDir(project.getGitDir());
        new CloneCommand(project.getGitDir()).mirror(true).from(repositoryUrl).call();
        checkSanity(project);
        
        listenerRegistry.post(new ProjectCreated(project));
        
        List<ImmutableTriple<String, ObjectId, ObjectId>> refUpdatedEventData = new ArrayList<>();
        
        for (RefInfo refInfo: project.getBranchRefInfos()) {
        	refUpdatedEventData.add(new ImmutableTriple<>(refInfo.getRef().getName(), 
        			ObjectId.zeroId(), refInfo.getObj().getId().copy()));
        }
        for (RefInfo refInfo: project.getTagRefInfos()) {
        	refUpdatedEventData.add(new ImmutableTriple<>(refInfo.getRef().getName(), 
        			ObjectId.zeroId(), refInfo.getPeeledObj().getId().copy()));
        }
        
        Long projectId = project.getId();
        
        sessionManager.runAsyncAfterCommit(new Runnable() {

			@Override
			public void run() {
		        try {
		            Project project = load(projectId);

		            for (ImmutableTriple<String, ObjectId, ObjectId> each: refUpdatedEventData) {
		            	String refName = each.getLeft();
		            	ObjectId oldObjectId = each.getMiddle();
		            	ObjectId newObjectId = each.getRight();
			        	if (!newObjectId.equals(ObjectId.zeroId()))
			        		project.cacheObjectId(refName, newObjectId);
			        	else 
			        		project.cacheObjectId(refName, null);
		            	
			        	listenerRegistry.post(new RefUpdated(project, refName, oldObjectId, newObjectId));
		            }
		        } catch (Exception e) {
		        	logger.error("Error posting ref updated event", e);
				}
			}
        	
        });
        
    }
    
	private boolean isGitHookValid(File gitDir, String hookName) {
        File hookFile = new File(gitDir, "hooks/" + hookName);
        if (!hookFile.exists()) 
        	return false;
        
        try {
			String content = FileUtils.readFileToString(hookFile, Charset.defaultCharset());
			if (!content.contains("ONEDEV_HOOK_TOKEN"))
				return false;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		
        if (!hookFile.canExecute())
        	return false;
        
        return true;
	}
	
	private void checkSanity(Project project) {
		File gitDir = project.getGitDir();
		if (gitDir.listFiles().length == 0) {
        	logger.info("Initializing git repository in '" + gitDir + "'...");
            try (Git git = Git.init().setDirectory(gitDir).setBare(true).call()) {
			} catch (Exception e) {
				throw ExceptionUtils.unchecked(e);
			}
		} else if (!GitUtils.isValid(gitDir)) {
        	logger.warn("Directory '" + gitDir + "' is not a valid git repository, reinitializing...");
        	FileUtils.cleanDir(gitDir);
            try (Git git = Git.init().setDirectory(gitDir).setBare(true).call()) {
			} catch (Exception e) {
				throw ExceptionUtils.unchecked(e);
			}
        } 

		if (!isGitHookValid(gitDir, "pre-receive") || !isGitHookValid(gitDir, "post-receive")) {
            File hooksDir = new File(gitDir, "hooks");

            File gitPreReceiveHookFile = new File(hooksDir, "pre-receive");
            FileUtils.writeFile(gitPreReceiveHookFile, String.format(gitReceiveHook, "git-prereceive-callback"));
            gitPreReceiveHookFile.setExecutable(true);
            
            File gitPostReceiveHookFile = new File(hooksDir, "post-receive");
            FileUtils.writeFile(gitPostReceiveHookFile, String.format(gitReceiveHook, "git-postreceive-callback"));
            gitPostReceiveHookFile.setExecutable(true);
        }

		try {
			StoredConfig config = project.getRepository().getConfig();
			boolean changed = false;
			if (config.getEnum(ConfigConstants.CONFIG_DIFF_SECTION, null, ConfigConstants.CONFIG_KEY_ALGORITHM, 
					SupportedAlgorithm.MYERS) != SupportedAlgorithm.HISTOGRAM) {
				config.setEnum(ConfigConstants.CONFIG_DIFF_SECTION, null, ConfigConstants.CONFIG_KEY_ALGORITHM, 
						SupportedAlgorithm.HISTOGRAM);
				changed = true;
			}
			if (!config.getBoolean("uploadpack", "allowAnySHA1InWant", false)) {
				config.setBoolean("uploadpack", null, "allowAnySHA1InWant", true);
				changed = true;
			}
			if (changed)
				config.save();				
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	@Listen
	public void on(SystemStopping event) {
		taskScheduler.unschedule(taskId);
		synchronized(repositoryCache) {
			for (Repository repository: repositoryCache.values()) {
				repository.close();
			}
		}
	}

	@Transactional
	@Listen
	public void on(ProjectEvent event) {
		/*
		 * Update asynchronously to avoid deadlock 
		 */
		updateDates.put(event.getProject().getId(), event.getDate());
	}
	
	@Transactional
	@Listen(1)
	public void on(SystemStarted event) {
		logger.info("Checking projects...");
		cacheLock.writeLock().lock();
		try {
			for (Project project: query()) {
				String path = project.getPath();
				if (!path.equals(project.calcPath())) { 
					System.out.println("shit");
					project.setPath(path);
				}
				cache.put(project.getId(), project.getFacade());
				checkSanity(project);
			}
		} finally {
			cacheLock.writeLock().unlock();
		}
		taskId = taskScheduler.schedule(this);
	}

	@Transactional
	@Override
	public void onDeleteBranch(Project project, String branchName) {
		for (Iterator<BranchProtection> it = project.getBranchProtections().iterator(); it.hasNext();) { 
			BranchProtection protection = it.next();
			PatternSet patternSet = PatternSet.parse(protection.getBranches());
			patternSet.getIncludes().remove(branchName);
			patternSet.getExcludes().remove(branchName);
			protection.setBranches(patternSet.toString());
			if (protection.getBranches().length() == 0)
				it.remove();
		}
	}
	
	@Transactional
	@Override
	public void deleteBranch(Project project, String branchName) {
		onDeleteBranch(project, branchName);

		String refName = GitUtils.branch2ref(branchName);
    	ObjectId commitId = project.getObjectId(refName, true);
    	try {
			project.git().branchDelete().setForce(true).setBranchNames(branchName).call();
		} catch (Exception e) {
			throw ExceptionUtils.unchecked(e);
		}
    	
    	Long projectId = project.getId();
    	sessionManager.runAsyncAfterCommit(new Runnable() {

			@Override
			public void run() {
				Project project = load(projectId);
				listenerRegistry.post(new RefUpdated(project, refName, commitId, ObjectId.zeroId()));
			}
    		
    	});
		
	}

	@Transactional
	@Override
	public void onDeleteTag(Project project, String tagName) {
		for (Iterator<TagProtection> it = project.getTagProtections().iterator(); it.hasNext();) { 
			TagProtection protection = it.next();
			PatternSet patternSet = PatternSet.parse(protection.getTags());
			patternSet.getIncludes().remove(tagName);
			patternSet.getExcludes().remove(tagName);
			protection.setTags(patternSet.toString());
			if (protection.getTags().length() == 0)
				it.remove();
		}
	}
	
	@Transactional
	@Override
	public void deleteTag(Project project, String tagName) {
    	onDeleteTag(project, tagName);
    	
    	String refName = GitUtils.tag2ref(tagName);
    	ObjectId commitId = project.getRevCommit(refName, true).getId();
    	try {
			project.git().tagDelete().setTags(tagName).call();
		} catch (GitAPIException e) {
			throw new RuntimeException(e);
		}

    	Long projectId = project.getId();
    	sessionManager.runAsyncAfterCommit(new Runnable() {

			@Override
			public void run() {
				Project project = load(projectId);
				listenerRegistry.post(new RefUpdated(project, refName, commitId, ObjectId.zeroId()));
			}
    		
    	});
	}
	
	@Override
	public List<Project> query() {
		return query(true);
	}

	@Override
	public int count() {
		return count(true);
	}
	
	private void addSubTreeIds(Collection<Long> projectIds, Project project) {
		projectIds.add(project.getId());
		for (Project descendant: project.getDescendants())
			projectIds.add(descendant.getId());
	}
	
	@Override
	public Collection<Project> getPermittedProjects(Permission permission) {
		ProjectCache cacheClone;
		cacheLock.readLock().lock();
		try {
			cacheClone = cache.clone();
		} finally {
			cacheLock.readLock().unlock();
		}
		
		Collection<Long> permittedProjectIds;
		User user = SecurityUtils.getUser();
        if (user != null) { 
        	if (user.isRoot() || user.isSystem()) { 
       			return cacheClone.getProjects();
        	} else {
        		permittedProjectIds = new HashSet<>();
               	for (Group group: user.getGroups()) {
               		if (group.isAdministrator())
               			return cacheClone.getProjects();
               		for (GroupAuthorization authorization: group.getAuthorizations()) {
               			if (authorization.getRole().implies(permission)) 
               				addSubTreeIds(permittedProjectIds, authorization.getProject());
               		}
               	}
               	Group defaultLoginGroup = settingManager.getSecuritySetting().getDefaultLoginGroup();
           		if (defaultLoginGroup != null) {
               		if (defaultLoginGroup.isAdministrator())
               			return cacheClone.getProjects();
               		for (GroupAuthorization authorization: defaultLoginGroup.getAuthorizations()) {
               			if (authorization.getRole().implies(permission)) 
               				addSubTreeIds(permittedProjectIds, authorization.getProject());
               		}
           		}
           		
	        	for (UserAuthorization authorization: user.getProjectAuthorizations()) { 
           			if (authorization.getRole().implies(permission)) 
           				addSubTreeIds(permittedProjectIds, authorization.getProject());
	        	}
	        	addIdsPermittedByDefaultRole(cacheClone, permittedProjectIds, permission);
        	}
        } else {
    		permittedProjectIds = new HashSet<>();
    		if (settingManager.getSecuritySetting().isEnableAnonymousAccess())
    			addIdsPermittedByDefaultRole(cacheClone, permittedProjectIds, permission);
        } 
        
        return permittedProjectIds.stream().map(it->load(it)).collect(Collectors.toSet());
	}	
	
	private void addIdsPermittedByDefaultRole(ProjectCache cache, Collection<Long> projectIds, 
			Permission permission) {
		for (ProjectFacade project: cache.values()) {
			if (project.getDefaultRoleId() != null) {
				Role defaultRole = roleManager.load(project.getDefaultRoleId());
				if (defaultRole.implies(permission)) 
					projectIds.addAll(cache.getSubtreeIds(project.getId()));
			}
		}
	}
	
	private CriteriaQuery<Project> buildCriteriaQuery(Session session, EntityQuery<Project> projectQuery) {
		CriteriaBuilder builder = session.getCriteriaBuilder();
		CriteriaQuery<Project> query = builder.createQuery(Project.class);
		Root<Project> root = query.from(Project.class);
		query.select(root);
		
		query.where(getPredicates(projectQuery.getCriteria(), query, root, builder));

		List<javax.persistence.criteria.Order> orders = new ArrayList<>();
		for (EntitySort sort: projectQuery.getSorts()) {
			if (sort.getDirection() == Direction.ASCENDING)
				orders.add(builder.asc(ProjectQuery.getPath(root, Project.ORDER_FIELDS.get(sort.getField()))));
			else
				orders.add(builder.desc(ProjectQuery.getPath(root, Project.ORDER_FIELDS.get(sort.getField()))));
		}

		if (orders.isEmpty())
			orders.add(builder.asc(ProjectQuery.getPath(root, Project.PROP_PATH)));
		query.orderBy(orders);
		
		return query;
	}
	
	private Predicate[] getPredicates(@Nullable Criteria<Project> criteria, CriteriaQuery<?> query, 
			From<Project, Project> from, CriteriaBuilder builder) {
		List<Predicate> predicates = new ArrayList<>();
		if (!SecurityUtils.isAdministrator()) {
			Collection<Project> projects = getPermittedProjects(new AccessProject());
			if (!projects.isEmpty()) {
				predicates.add(Criteria.forManyValues(builder, from.get(Project.PROP_ID), 
						projects.stream().map(it->it.getId()).collect(Collectors.toSet()), getIds()));
			} else {
				predicates.add(builder.disjunction());
			}
		}
		if (criteria != null) 
			predicates.add(criteria.getPredicate(query, from, builder));
		return predicates.toArray(new Predicate[0]);
	}
	
	@Sessional
	@Override
	public List<Project> query(EntityQuery<Project> query, int firstResult, int maxResults) {
		CriteriaQuery<Project> criteriaQuery = buildCriteriaQuery(getSession(), query);
		Query<Project> projectQuery = getSession().createQuery(criteriaQuery);
		projectQuery.setFirstResult(firstResult);
		projectQuery.setMaxResults(maxResults);
		return projectQuery.getResultList();
	}

	@Sessional
	@Override
	public int count(Criteria<Project> projectCriteria) {
		CriteriaBuilder builder = getSession().getCriteriaBuilder();
		CriteriaQuery<Long> criteriaQuery = builder.createQuery(Long.class);
		Root<Project> root = criteriaQuery.from(Project.class);

		criteriaQuery.where(getPredicates(projectCriteria, criteriaQuery, root, builder));

		criteriaQuery.select(builder.count(root));
		return getSession().createQuery(criteriaQuery).uniqueResult().intValue();
	}

	@Override
	public void execute() {
		try {
			transactionManager.run(new Runnable() {
	
				@Override
				public void run() {
					Date now = new Date();
					for (Iterator<Map.Entry<Long, Date>> it = updateDates.entrySet().iterator(); it.hasNext();) {
						Map.Entry<Long, Date> entry = it.next();
						if (now.getTime() - entry.getValue().getTime() > 60000) {
							Project project = get(entry.getKey());
							if (project != null)
								project.setUpdateDate(entry.getValue());
							it.remove();
						}
					}
				}
				
			});
		} catch (Exception e) {
			logger.error("Error flushing project update dates", e);
		}
	}
	
	@Override
	public ScheduleBuilder<?> getScheduleBuilder() {
		return SimpleScheduleBuilder.repeatMinutelyForever();
	}
	
	@Override
	public Collection<Long> getSubtreeIds(Long projectId) {
		cacheLock.readLock().lock();
		try {
			return cache.getSubtreeIds(projectId);
		} finally {
			cacheLock.readLock().unlock();
		}
	}
	
	@Override
	public Collection<Long> getIds() {
		cacheLock.readLock().lock();
		try {
			return new HashSet<>(cache.keySet());
		} finally {
			cacheLock.readLock().unlock();
		}
	}
	
	@Override
	public Predicate getPathMatchPredicate(CriteriaBuilder builder, Path<Project> path, String pathPattern) {
		cacheLock.readLock().lock();
		try {
			return Criteria.forManyValues(builder, path.get(Project.PROP_ID), 
					cache.getMatchingIds(pathPattern), cache.keySet());		
		} finally {
			cacheLock.readLock().unlock();
		}
	}
	
	@Transactional
	@Override
	public void move(Collection<Project> projects, Project parent) {
		for (Project project: projects) { 
			project.setParent(parent);
			save(project);
		}
	}

	@Transactional
	@Override
	public void delete(Collection<Project> projects) {
		Collection<Project> independents = new HashSet<>(projects);
		for (Iterator<Project> it = independents.iterator(); it.hasNext();) {
			Project independent = it.next();
			for (Project each: independents) {
				if (!each.equals(independent) && each.isSelfOrAncestorOf(independent)) {
					it.remove();
					break;
				}
			}
		}
		for (Project independent: independents)
			delete(independent);
	}
    
	@Override
	public List<ProjectFacade> getChildren(Long projectId) {
		cacheLock.readLock().lock();
		try {
			return cache.getChildren(projectId);
		} finally {
			cacheLock.readLock().unlock();
		}
	}

	@Override
	public ProjectCache cloneCache() {
		cacheLock.readLock().lock();
		try {
			return cache.clone();
		} finally {
			cacheLock.readLock().unlock();
		}
	}

	@Override
	public String getFavoriteQuery() {
		User user = SecurityUtils.getUser();
		if (user != null && !user.getProjectQueryPersonalization().getQueries().isEmpty()) {
			return user.getProjectQueryPersonalization().getQueries().iterator().next().getQuery();
		} else {
			GlobalProjectSetting projectSetting = settingManager.getProjectSetting();
			if (!projectSetting.getNamedQueries().isEmpty())
				return projectSetting.getNamedQueries().iterator().next().getQuery();
		}
		return null;
	}
    
}
