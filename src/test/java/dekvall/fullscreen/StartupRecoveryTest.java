package dekvall.fullscreen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class StartupRecoveryTest
{
	@Rule
	public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void interruptedStartupIsDetectedOnNextLaunch() throws Exception
	{
		Path directory = temporary.newFolder().toPath();
		StartupRecovery previous = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		assertEquals(StartupRecovery.Result.READY, previous.begin());
		assertTrue(Files.exists(directory.resolve("startup.pending")));
		previous.finish(false); // Models the lock being released while the marker survives.
		StartupRecovery next = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		assertEquals(StartupRecovery.Result.RECOVERED, next.begin());
		assertFalse(Files.exists(directory.resolve("startup.pending")));
		next.finish(true);
	}

	@Test
	public void successfulOrIntentionallyCancelledStartupDoesNotTriggerRecovery() throws Exception
	{
		Path directory = temporary.newFolder().toPath();
		StartupRecovery previous = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		assertEquals(StartupRecovery.Result.READY, previous.begin());
		previous.finish(true);
		StartupRecovery next = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		assertEquals(StartupRecovery.Result.READY, next.begin());
		next.finish(true);
	}

	@Test
	public void anotherLiveClientDoesNotConsumeOrDeletePendingMarker() throws Exception
	{
		Path directory = temporary.newFolder().toPath();
		StartupRecovery first = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		StartupRecovery second = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		assertEquals(StartupRecovery.Result.READY, first.begin());
		try
		{
			assertEquals(StartupRecovery.Result.BUSY, second.begin());
			second.finish(true);
			assertTrue(Files.exists(directory.resolve("startup.pending")));
		}
		finally
		{
			first.finish(true);
		}
		assertEquals(StartupRecovery.Result.READY, second.begin());
		second.finish(true);
	}

	@Test
	public void independentProfilesDoNotBlockEachOther() throws Exception
	{
		Path directory = temporary.newFolder().toPath();
		StartupRecovery first = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory.resolve("profile-one")));
		StartupRecovery second = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory.resolve("profile-two")));
		assertEquals(StartupRecovery.Result.READY, first.begin());
		try
		{
			assertEquals(StartupRecovery.Result.READY, second.begin());
		}
		finally
		{
			first.finish(true);
			second.finish(true);
		}
	}

	@Test
	public void unwritableMarkerLocationRejectsAutomaticStartup() throws Exception
	{
		Path file = temporary.newFile().toPath();
		StartupRecovery recovery = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(file));
		assertThrows(IOException.class, recovery::begin);
		recovery.finish(true);
	}

	@Test
	public void launchCheckFindsAndRemovesALeftoverMarker() throws Exception
	{
		Path directory = temporary.newFolder().toPath();
		StartupRecovery check = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		assertEquals("Nothing left behind", StartupRecovery.Result.READY, check.check());
		StartupRecovery crashed = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		assertEquals(StartupRecovery.Result.READY, crashed.begin());
		crashed.finish(false); // the process died with the marker armed
		assertEquals(StartupRecovery.Result.RECOVERED, check.check());
		assertFalse(Files.exists(directory.resolve("startup.pending")));
		assertEquals("Reported once", StartupRecovery.Result.READY, check.check());
		assertEquals("A later change can arm it again", StartupRecovery.Result.READY, check.begin());
		check.finish(true);
	}

	@Test
	public void launchCheckLeavesAnotherLiveClientsMarkerAlone() throws Exception
	{
		Path directory = temporary.newFolder().toPath();
		StartupRecovery live = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
		assertEquals(StartupRecovery.Result.READY, live.begin());
		try
		{
			StartupRecovery check = new StartupRecovery(net.runelite.client.util.Filepath.Unchecked.getRooted(directory));
			assertEquals(StartupRecovery.Result.BUSY, check.check());
			assertTrue(Files.exists(directory.resolve("startup.pending")));
		}
		finally
		{
			live.finish(true);
		}
	}
}
