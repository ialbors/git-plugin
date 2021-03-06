/*
 * The MIT License
 *
 * Copyright (c) 2010, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.git;

import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.matrix.Axis;
import hudson.matrix.AxisList;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;
import hudson.model.*;
import hudson.plugins.git.GitPublisher.BranchToPush;
import hudson.plugins.git.GitPublisher.NoteToPush;
import hudson.plugins.git.GitPublisher.TagToPush;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.impl.LocalBranch;
import hudson.plugins.git.extensions.impl.PreBuildMerge;
import hudson.scm.NullSCM;
import hudson.tasks.BuildStepDescriptor;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.jvnet.hudson.test.Bug;
import org.jvnet.hudson.test.Issue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for {@link GitPublisher}
 * 
 * @author Kohsuke Kawaguchi
 */
public class GitPublisherTest extends AbstractGitTestCase {
    @Bug(5005)
    public void testMatrixBuild() throws Exception {
        final AtomicInteger run = new AtomicInteger(); // count the number of times the perform is called

        commit("a", johnDoe, "commit #1");

        MatrixProject mp = createMatrixProject("xyz");
        mp.setAxes(new AxisList(new Axis("VAR","a","b")));
        mp.setScm(new GitSCM(workDir.getAbsolutePath()));
        mp.getPublishersList().add(new GitPublisher(
                Collections.singletonList(new TagToPush("origin","foo","message",true, false)),
                Collections.<BranchToPush>emptyList(),
                Collections.<NoteToPush>emptyList(),
                true, true, false) {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
                run.incrementAndGet();
                try {
                    return super.perform(build, launcher, listener);
                } finally {
                    // until the 3rd one (which is the last one), we shouldn't create a tag
                    if (run.get()<3)
                        assertFalse(existsTag("foo"));
                }
            }

            @Override
            public BuildStepDescriptor getDescriptor() {
                return (BuildStepDescriptor)Hudson.getInstance().getDescriptorOrDie(GitPublisher.class); // fake
            }

            private Object writeReplace() { return new NullSCM(); }
        });

        MatrixBuild b = assertBuildStatusSuccess(mp.scheduleBuild2(0).get());
        System.out.println(b.getLog());

        assertTrue(existsTag("foo"));

        assertTrue(containsTagMessage("foo", "message"));

        // twice for MatrixRun, which is to be ignored, then once for matrix completion
        assertEquals(3,run.get());
    }

    public void testMergeAndPush() throws Exception {
        FreeStyleProject project = setupSimpleProject("master");

        GitSCM scm = new GitSCM(
                createRemoteRepositories(),
                Collections.singletonList(new BranchSpec("*")),
                false, Collections.<SubmoduleConfig>emptyList(),
                null, null,
                Collections.<GitSCMExtension>emptyList());
        scm.getExtensions().add(new PreBuildMerge(new UserMergeOptions("origin", "integration", null)));
        scm.getExtensions().add(new LocalBranch("integration"));
        project.setScm(scm);

        project.getPublishersList().add(new GitPublisher(
                Collections.<TagToPush>emptyList(),
                Collections.singletonList(new BranchToPush("origin", "integration")),
                Collections.<NoteToPush>emptyList(),
                true, true, false));

        // create initial commit and then run the build against it:
        commit("commitFileBase", johnDoe, "Initial Commit");
        testRepo.git.branch("integration");
        build(project, Result.SUCCESS, "commitFileBase");

        testRepo.git.checkout(null, "topic1");
        final String commitFile1 = "commitFile1";
        commit(commitFile1, johnDoe, "Commit number 1");
        final FreeStyleBuild build1 = build(project, Result.SUCCESS, commitFile1);
        assertTrue(build1.getWorkspace().child(commitFile1).exists());

        String sha1 = getHeadRevision(build1, "integration");
        assertEquals(sha1, testRepo.git.revParse(Constants.HEAD).name());

    }
    
    @Issue("JENKINS-24082")
    public void testForcePush() throws Exception {
    	FreeStyleProject project = setupSimpleProject("master");

        GitSCM scm = new GitSCM(
                createRemoteRepositories(),
                Collections.singletonList(new BranchSpec("master")),
                false, Collections.<SubmoduleConfig>emptyList(),
                null, null,
                Collections.<GitSCMExtension>emptyList());
        project.setScm(scm);

        project.getPublishersList().add(new GitPublisher(
                Collections.<TagToPush>emptyList(),
                Collections.singletonList(new BranchToPush("origin", "otherbranch")),
                Collections.<NoteToPush>emptyList(),
                true, true, true));

        commit("commitFile", johnDoe, "Initial Commit");

        testRepo.git.branch("otherbranch");
        testRepo.git.checkout("otherbranch");
        commit("otherCommitFile", johnDoe, "commit lost on force push");
        
        testRepo.git.checkout("master");
        commit("commitFile2", johnDoe, "commit to be pushed");
        
        ObjectId expectedCommit = testRepo.git.revParse("master");
        
        build(project, Result.SUCCESS, "commitFile");

        assertEquals(expectedCommit, testRepo.git.revParse("otherbranch"));
    }

    /**
     * Fix push to remote when skipTag is enabled
     */
    @Bug(17769)
    public void testMergeAndPushWithSkipTagEnabled() throws Exception {
      FreeStyleProject project = setupSimpleProject("master");

        GitSCM scm = new GitSCM(
                createRemoteRepositories(),
                Collections.singletonList(new BranchSpec("*")),
                false, Collections.<SubmoduleConfig>emptyList(),
                null, null, new ArrayList<GitSCMExtension>());
        scm.getExtensions().add(new PreBuildMerge(new UserMergeOptions("origin", "integration", null)));
        scm.getExtensions().add(new LocalBranch("integration"));
        project.setScm(scm);


      project.getPublishersList().add(new GitPublisher(
          Collections.<TagToPush>emptyList(),
          Collections.singletonList(new BranchToPush("origin", "integration")),
          Collections.<NoteToPush>emptyList(),
          true, true, false));

      // create initial commit and then run the build against it:
      commit("commitFileBase", johnDoe, "Initial Commit");
      testRepo.git.branch("integration");
      build(project, Result.SUCCESS, "commitFileBase");

      testRepo.git.checkout(null, "topic1");
      final String commitFile1 = "commitFile1";
      commit(commitFile1, johnDoe, "Commit number 1");
      final FreeStyleBuild build1 = build(project, Result.SUCCESS, commitFile1);
      assertTrue(build1.getWorkspace().child(commitFile1).exists());

      String sha1 = getHeadRevision(build1, "integration");
      assertEquals(sha1, testRepo.git.revParse(Constants.HEAD).name());
    }

    @Bug(24786)
    public void testMergeAndPushWithCharacteristicEnvVar() throws Exception {
        FreeStyleProject project = setupSimpleProject("master");

        /*
         * JOB_NAME seemed like the more obvious choice, but when run from a 
         * multi-configuration job, the value of JOB_NAME includes an equals
         * sign.  That makes log parsing and general use of the variable more
         * difficult.  JENKINS_SERVER_COOKIE is a characteristic env var which
         * probably never includes an equals sign.
         */
        String envName = "JENKINS_SERVER_COOKIE";
        String envValue = project.getCharacteristicEnvVars().get(envName, "NOT-SET");
        assertFalse("Env " + envName + " not set", envValue.equals("NOT-SET"));

        checkEnvVar(project, envName, envValue);
    }

    @Bug(24786)
    public void testMergeAndPushWithSystemEnvVar() throws Exception {
        FreeStyleProject project = setupSimpleProject("master");

        String envName = Functions.isWindows() ? "COMPUTERNAME" : "LOGNAME";
        String envValue = System.getenv().get(envName);
        assertNotNull("Env " + envName + " not set", envValue);
        assertFalse("Env " + envName + " empty", envValue.isEmpty());

        checkEnvVar(project, envName, envValue);
    }

    private void checkEnvVar(FreeStyleProject project, String envName, String envValue) throws Exception {

        String envReference = "${" + envName + "}";

        List<GitSCMExtension> scmExtensions = new ArrayList<GitSCMExtension>();
        scmExtensions.add(new PreBuildMerge(new UserMergeOptions("origin", envReference, null)));
        scmExtensions.add(new LocalBranch(envReference));
        GitSCM scm = new GitSCM(
                createRemoteRepositories(),
                Collections.singletonList(new BranchSpec("*")),
                false, Collections.<SubmoduleConfig>emptyList(),
                null, null, scmExtensions);
        project.setScm(scm);

        String tagNameReference = envReference + "-tag"; // ${BRANCH_NAME}-tag
        String tagNameValue = envValue + "-tag";         // master-tag
        String tagMessageReference = envReference + " tag message";
        String noteReference = "note for " + envReference;
        String noteValue = "note for " + envValue;
        GitPublisher publisher = new GitPublisher(
                Collections.singletonList(new TagToPush("origin", tagNameReference, tagMessageReference, false, true)),
                Collections.singletonList(new BranchToPush("origin", envReference)),
                Collections.singletonList(new NoteToPush("origin", noteReference, Constants.R_NOTES_COMMITS, false)),
                true, true, true);
        assertTrue(publisher.isForcePush());
        assertTrue(publisher.isPushBranches());
        assertTrue(publisher.isPushMerge());
        assertTrue(publisher.isPushNotes());
        assertTrue(publisher.isPushOnlyIfSuccess());
        assertTrue(publisher.isPushTags());
        project.getPublishersList().add(publisher);

        // create initial commit
        commit("commitFileBase", johnDoe, "Initial Commit");
        ObjectId initialCommit = testRepo.git.getHeadRev(testRepo.gitDir.getAbsolutePath(), "master");
        assertTrue(testRepo.git.isCommitInRepo(initialCommit));

        // Create branch in the test repo (pulled into the project workspace at build)
        assertFalse("Test repo has " + envValue + " branch", hasBranch(envValue));
        testRepo.git.branch(envValue);
        assertTrue("Test repo missing " + envValue + " branch", hasBranch(envValue));
        assertFalse(tagNameValue + " in " + testRepo, testRepo.git.tagExists(tagNameValue));

        // Build the branch
        final FreeStyleBuild build0 = build(project, Result.SUCCESS, "commitFileBase");

        String build0HeadBranch = getHeadRevision(build0, envValue);
        assertEquals(build0HeadBranch, initialCommit.getName());
        assertTrue(tagNameValue + " not in " + testRepo, testRepo.git.tagExists(tagNameValue));
        assertTrue(tagNameValue + " not in build", build0.getWorkspace().child(".git/refs/tags/" + tagNameValue).exists());

        // Create a topic branch in the source repository and commit to topic branch
        String topicBranch = envValue + "-topic1";
        assertFalse("Test repo has " + topicBranch + " branch", hasBranch(topicBranch));
        testRepo.git.checkout(null, topicBranch);
        assertTrue("Test repo has no " + topicBranch + " branch", hasBranch(topicBranch));
        final String commitFile1 = "commitFile1";
        commit(commitFile1, johnDoe, "Commit number 1");
        ObjectId topicCommit = testRepo.git.getHeadRev(testRepo.gitDir.getAbsolutePath(), topicBranch);
        assertTrue(testRepo.git.isCommitInRepo(topicCommit));

        // Run a build, should be on the topic branch, tagged, and noted
        final FreeStyleBuild build1 = build(project, Result.SUCCESS, commitFile1);
        FilePath myWorkspace = build1.getWorkspace();
        assertTrue(myWorkspace.child(commitFile1).exists());
        assertTrue("Tag " + tagNameValue + " not in build", myWorkspace.child(".git/refs/tags/" + tagNameValue).exists());

        String build1Head = getHeadRevision(build1, envValue);
        assertEquals(build1Head, testRepo.git.revParse(Constants.HEAD).name());
        assertEquals("Wrong head commit in build1", topicCommit.getName(), build1Head);

    }

    private boolean existsTag(String tag) throws InterruptedException {
        Set<String> tags = git.getTagNames("*");
        return tags.contains(tag);
    }

    private boolean containsTagMessage(String tag, String str) throws InterruptedException {
        String msg = git.getTagMessage(tag);
        return msg.contains(str);
    }

    private boolean hasBranch(String branchName) throws GitException, InterruptedException {
        Set<Branch> testRepoBranches = testRepo.git.getBranches();
        for (Branch branch : testRepoBranches) {
            if (branch.getName().equals(branchName)) {
                return true;
            }
        }
        return false;
    }
}
