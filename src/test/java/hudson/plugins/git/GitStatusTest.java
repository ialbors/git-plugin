/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hudson.plugins.git;

import hudson.model.FreeStyleProject;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.impl.IgnoreNotifyCommit;
import hudson.plugins.git.util.DefaultBuildChooser;
import hudson.triggers.SCMTrigger;

import org.eclipse.jgit.transport.URIish;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jvnet.hudson.test.HudsonTestCase;
import org.mockito.Mockito;

public class GitStatusTest extends HudsonTestCase {
    private GitStatus gitStatus;

    public GitStatusTest(String testName) {
        super(testName);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        this.gitStatus = new GitStatus();
    }

    public void testGetDisplayName() {
        assertEquals("Git", this.gitStatus.getDisplayName());
    }

    public void testGetSearchUrl() {
        assertEquals("git", this.gitStatus.getSearchUrl());
    }

    public void testGetIconFileName() {
        assertNull(this.gitStatus.getIconFileName());
    }

    public void testGetUrlName() {
        assertEquals("git", this.gitStatus.getUrlName());
    }

    public void testDoNotifyCommitWithNoBranches() throws Exception {
        SCMTrigger aMasterTrigger = setupProject("a", "master", false);
        SCMTrigger aTopicTrigger = setupProject("a", "topic", false);
        SCMTrigger bMasterTrigger = setupProject("b", "master", false);
        SCMTrigger bTopicTrigger = setupProject("b", "topic", false);

        this.gitStatus.doNotifyCommit("a", null, "");
        Mockito.verify(aMasterTrigger).run();
        Mockito.verify(aTopicTrigger).run();
        Mockito.verify(bMasterTrigger, Mockito.never()).run();
        Mockito.verify(bTopicTrigger, Mockito.never()).run();
    }

    public void testDoNotifyCommitWithNoMatchingUrl() throws Exception {
        SCMTrigger aMasterTrigger = setupProject("a", "master", false);
        SCMTrigger aTopicTrigger = setupProject("a", "topic", false);
        SCMTrigger bMasterTrigger = setupProject("b", "master", false);
        SCMTrigger bTopicTrigger = setupProject("b", "topic", false);

        this.gitStatus.doNotifyCommit("nonexistent", null, "");
        Mockito.verify(aMasterTrigger, Mockito.never()).run();
        Mockito.verify(aTopicTrigger, Mockito.never()).run();
        Mockito.verify(bMasterTrigger, Mockito.never()).run();
        Mockito.verify(bTopicTrigger, Mockito.never()).run();
    }

    public void testDoNotifyCommitWithOneBranch() throws Exception {
        SCMTrigger aMasterTrigger = setupProject("a", "master", false);
        SCMTrigger aTopicTrigger = setupProject("a", "topic", false);
        SCMTrigger bMasterTrigger = setupProject("b", "master", false);
        SCMTrigger bTopicTrigger = setupProject("b", "topic", false);

        this.gitStatus.doNotifyCommit("a", null, "master");
        Mockito.verify(aMasterTrigger).run();
        Mockito.verify(aTopicTrigger, Mockito.never()).run();
        Mockito.verify(bMasterTrigger, Mockito.never()).run();
        Mockito.verify(bTopicTrigger, Mockito.never()).run();
    }

    public void testDoNotifyCommitWithTwoBranches() throws Exception {
        SCMTrigger aMasterTrigger = setupProject("a", "master", false);
        SCMTrigger aTopicTrigger = setupProject("a", "topic", false);
        SCMTrigger bMasterTrigger = setupProject("b", "master", false);
        SCMTrigger bTopicTrigger = setupProject("b", "topic", false);

        this.gitStatus.doNotifyCommit("a", null, "master,topic");
        Mockito.verify(aMasterTrigger).run();
        Mockito.verify(aTopicTrigger).run();
        Mockito.verify(bMasterTrigger, Mockito.never()).run();
        Mockito.verify(bTopicTrigger, Mockito.never()).run();
    }

    public void testDoNotifyCommitWithNoMatchingBranches() throws Exception {
        SCMTrigger aMasterTrigger = setupProject("a", "master", false);
        SCMTrigger aTopicTrigger = setupProject("a", "topic", false);
        SCMTrigger bMasterTrigger = setupProject("b", "master", false);
        SCMTrigger bTopicTrigger = setupProject("b", "topic", false);

        this.gitStatus.doNotifyCommit("a", null, "nonexistent");
        Mockito.verify(aMasterTrigger, Mockito.never()).run();
        Mockito.verify(aTopicTrigger, Mockito.never()).run();
        Mockito.verify(bMasterTrigger, Mockito.never()).run();
        Mockito.verify(bTopicTrigger, Mockito.never()).run();
    }

    public void testDoNotifyCommitWithIgnoredRepository() throws Exception {
        SCMTrigger aMasterTrigger = setupProject("a", "master", true);

        this.gitStatus.doNotifyCommit("a", null, "");
        Mockito.verify(aMasterTrigger, Mockito.never()).run();
    }

    private SCMTrigger setupProject(String url, String branchString, boolean ignoreNotifyCommit) throws Exception {
        FreeStyleProject project = createFreeStyleProject();
        GitSCM git = new GitSCM(
                Collections.singletonList(new UserRemoteConfig(url, null, null, null)),
                Collections.singletonList(new BranchSpec(branchString)),
                false, Collections.<SubmoduleConfig>emptyList(),
                null, null,
                Collections.<GitSCMExtension>emptyList());
        if (ignoreNotifyCommit)
            git.getExtensions().add(new IgnoreNotifyCommit());
        project.setScm(git);
        SCMTrigger trigger = Mockito.mock(SCMTrigger.class);
        project.addTrigger(trigger);
        return trigger;
    }

    public void testLooseMatch() throws URISyntaxException {
        String[] list = new String[]{
            "https://github.com/jenkinsci/git-plugin.git",
            "git://github.com/jenkinsci/git-plugin.git",
            "ssh://git@github.com/jenkinsci/git-plugin.git",
            "https://someone@github.com/jenkinsci/git-plugin.git",
            "git@github.com:jenkinsci/git-plugin.git"
        };
        List<URIish> uris = new ArrayList<URIish>();
        for (String s : list) {
            uris.add(new URIish(s));
        }

        for (URIish lhs : uris) {
            for (URIish rhs : uris) {
                assertTrue(lhs+" and "+rhs+" didn't match",new GitStatus().looselyMatches(lhs,rhs));
            }
        }
    }
}
