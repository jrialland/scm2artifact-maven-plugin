package net.jr.scm2artifact;

import java.io.File;
import java.security.MessageDigest;
import java.util.Arrays;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuilderConfiguration;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.manager.BasicScmManager;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.git.jgit.JGitScmProvider;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.impl.StaticLoggerBinder;

@Mojo(name = "scm2artifact", requiresDependencyCollection = ResolutionScope.NONE, requiresDependencyResolution = ResolutionScope.NONE, defaultPhase = LifecyclePhase.INITIALIZE, executionStrategy = "always", requiresOnline = true)
@Execute(phase = LifecyclePhase.INITIALIZE)
public class Scm2ArtifactMojo extends AbstractMojo {

	private static Logger LOGGER = LoggerFactory
			.getLogger(Scm2ArtifactMojo.class);

	private static Logger getLogger() {
		return LOGGER;
	}

	@Component
	private RepositorySystem repositorySystem;

	@Parameter(defaultValue="${repositorySystemSession}", readonly=true)
	private RepositorySystemSession repositorySystemSession;

	@Parameter(defaultValue = "${localRepository}", required = true)
	protected ArtifactRepository localRepository;

	/**
	 * The project on which we are working
	 */
	@Parameter(defaultValue = "${project}", required = true)
	protected MavenProject project;

	@Component
	protected MavenProjectBuilder mavenProjectBuilder;
	
	/**
	 * The source control repository to check out. the format of the url is
	 * described <a
	 * href="http://maven.apache.org/scm/scm-url-format.html">here</a>
	 */
	@Parameter(property = "scmUrl", required = true)
	private String scmUrl;

	/**
	 * The path of the pom file, relative to the checked project. (default value
	 * = './pom.xml')
	 */
	@Parameter(property = "pomRelativePath", defaultValue = "./pom.xml")
	private String pomRelativePath;

	/**
	 * maven goals to be executed when building the project (default value =
	 * 'clean install')
	 */
	@Parameter(property = "mavenGoals", defaultValue = "clean install")
	private String mavenGoals;

	@Parameter(property = "maveProfiles", defaultValue = "")
	private String mavenProfiles = "";

	@Parameter(property = "skipCheckoutIfPresent", defaultValue = "false")
	private boolean skipCheckoutIfPresent = false;

	@Parameter(property = "skipBuildIfResolved", defaultValue = "true")
	private boolean skipBuildIfResolved = true;

	public void execute() throws MojoExecutionException, MojoFailureException {
		StaticLoggerBinder.getSingleton().setLog(getLog());

		if (getLogger().isDebugEnabled()) {
			getLogger().debug("scmUrl : " + scmUrl);
			getLogger().debug("pomRelativePath : " + pomRelativePath);
			getLogger().debug("mavenGoals : " + mavenGoals);

		}

		// create temporary directory
		File home = new File(System.getProperty("java.io.tmpdir"),
				"git2artifact");

		getLogger().debug(
				String.format("using %s as home directory", home.getPath()));

		if (!home.isDirectory() && !home.mkdirs()) {
			throw new MojoExecutionException("could not create "
					+ home.getPath());
		}

		// create a directory for the scm project
		getLogger().debug("creating checkout directory");
		File checkoutDir = new File(home, hash(scmUrl));
		if (!checkoutDir.isDirectory() && !checkoutDir.mkdirs()) {
			throw new MojoExecutionException("could not create "
					+ home.getPath());
		}
		getLogger()
				.info(String.format("checkout directory is "
						+ checkoutDir.getPath()));

		try {
			getLogger().debug("checking out");
			checkout(scmUrl, checkoutDir);
		} catch (Exception e) {
			throw new MojoExecutionException("error while checking out "
					+ scmUrl, e);
		}

		// locate pom file
		File pom = new File(checkoutDir, pomRelativePath);

		// checkout
		if (pom.isFile() && skipCheckoutIfPresent) {
			getLog().info("skipping checkout");
		} else {
			try {
				getLogger().debug("checking out");
				checkout(scmUrl, checkoutDir);
			} catch (Exception e) {
				throw new MojoExecutionException("error while checking out "
						+ scmUrl, e);
			}
		}

		// now the pom should exist
		if (!pom.isFile()) {
			throw new MojoExecutionException("file not found : "
					+ pom.getPath());
		}

		// extract target artifact name
		MavenProject targetProject = null;
		try {
			targetProject = readProjectFromPom(pom);
		} catch (Exception e) {
			throw new MojoExecutionException("could not read pom", e);
		}

		if (canResolve(targetProject) && skipBuildIfResolved) {
			getLog().info("target artifact is already resolved, skipping");
			return;
		} else {
			// run build
			InvocationRequest request = new DefaultInvocationRequest();
			request.setPomFile(pom);
			request.setGoals(Arrays.asList(mavenGoals.split(" ")));
			request.setProfiles(Arrays.asList(mavenProfiles.split(" ")));
			try {
				new DefaultInvoker().execute(request);
			} catch (MavenInvocationException e) {
				throw new MojoExecutionException("could not build", e);
			}
		}

	}

	protected boolean canResolve(MavenProject p) {
		try {
			StringBuilder sb = new StringBuilder();
			sb.append(p.getGroupId());
			sb.append(':');
			sb.append(p.getArtifactId());
			sb.append(':');
			sb.append(p.getVersion());
			sb.append(':');
			sb.append(p.getPackaging());

			String artifactSpec = sb.toString();

			getLogger().info("resolving " + artifactSpec);

			Artifact art = new DefaultArtifact(artifactSpec);
			ArtifactRequest request = new ArtifactRequest();
			request.setArtifact(art);
			ArtifactResult result = repositorySystem.resolveArtifact(
					repositorySystemSession, request);
			return result.isResolved();
		} catch (Exception e) {
			getLogger().warn("while resolving artifact", e);
			return false;
		}
	}

	/**
	 * reads a pom
	 * 
	 * @param pom
	 * @return
	 * @throws ProjectBuildingException
	 */
	protected MavenProject readProjectFromPom(File pom)
			throws ProjectBuildingException {
		DefaultProjectBuilderConfiguration configuration = new DefaultProjectBuilderConfiguration();
		configuration.setLocalRepository(localRepository);
		return mavenProjectBuilder.build(pom, configuration);
	}

	protected static void checkout(String scmUrl, File targetDir)
			throws ScmException {
		ScmManager scmManager = new BasicScmManager();
		scmManager.setScmProvider("git", new JGitScmProvider());
		ScmRepository repo = scmManager.makeScmRepository(scmUrl);
		scmManager.checkOut(repo, new ScmFileSet(targetDir));
	}

	private static final char[] hexchars = "0123456789abcdef".toCharArray();

	private static String hash(String txt) {
		StringBuilder sb = new StringBuilder();
		MessageDigest md;
		try {
			md = MessageDigest.getInstance("md5");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		for (byte b : md.digest(txt.getBytes())) {
			sb.append(hexchars[(b & 0xf0) >> 4]);
			sb.append(hexchars[b & 0x0f]);
		}
		return sb.toString();
	}
}
