package dekvall.fullscreen;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.gpu.GpuPlugin;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RendererSuspenderTest
{
	private PluginManager manager;
	private ClientThread clientThread;
	private Client client;

	@Before
	public void setUp()
	{
		manager = mock(PluginManager.class);
		clientThread = mock(ClientThread.class);
		client = mock(Client.class);
		doAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
	}

	/** PluginManager's real behavior: stopPlugin deactivates, startPlugin activates. */
	private void trackActive(Plugin... initiallyActive) throws Exception
	{
		java.util.Set<Plugin> active = new java.util.HashSet<>(Arrays.asList(initiallyActive));
		when(manager.isPluginActive(any())).thenAnswer(call -> active.contains(call.getArgument(0)));
		when(manager.stopPlugin(any())).thenAnswer(call -> active.remove(call.getArgument(0)));
		when(manager.startPlugin(any())).thenAnswer(call -> active.add(call.getArgument(0)));
		// Renderers that start and stop instantly, unless a test says otherwise.
		when(client.isGpu()).thenAnswer(call -> !active.isEmpty());
	}

	@Test
	public void missingCoreGpuIsSafe() throws Exception
	{
		when(manager.getPlugins()).thenReturn(Collections.emptyList());
		CountDownLatch ready = new CountDownLatch(1);
		FullscreenControllerTest.edt(() -> new RendererSuspender(manager, clientThread, client)
			.suspend(ready::countDown, ex -> fail(ex.toString())));
		assertTrue(ready.await(2, TimeUnit.SECONDS));
		verify(manager, never()).conflictsForPlugin(null);
	}

	@Test
	public void onlyPreviouslyActiveRenderersAreRestarted() throws Exception
	{
		Plugin gpu = mock(GpuPlugin.class);
		Plugin hd = mock(Plugin.class);
		when(manager.getPlugins()).thenReturn(Arrays.asList(gpu, hd));
		when(manager.conflictsForPlugin(gpu)).thenReturn(Collections.singletonList(hd));
		trackActive(hd);
		when(manager.isPluginEnabled(hd)).thenReturn(true);
		RendererSuspender suspender = new RendererSuspender(manager, clientThread, client);
		CountDownLatch done = new CountDownLatch(1);
		FullscreenControllerTest.edt(() -> suspender.suspend(() ->
		{
			suspender.resume();
			done.countDown();
		}, ex -> fail(ex.toString())));
		assertTrue(done.await(2, TimeUnit.SECONDS));
		verify(manager).stopPlugin(hd);
		verify(manager).startPlugin(hd);
		verify(manager, never()).stopPlugin(gpu);
		verify(manager, never()).startPlugin(gpu);
	}

	@Test
	public void timeoutDoesNotRunLateWindowCallback() throws Exception
	{
		when(manager.getPlugins()).thenReturn(Collections.emptyList());
		AtomicInteger windows = new AtomicInteger();
		CountDownLatch failed = new CountDownLatch(1);
		// Simulate a client thread that is stalled and cannot release the renderer.
		AtomicReference<Runnable> lateRead = new AtomicReference<>();
		doAnswer(invocation ->
		{
			lateRead.set(invocation.getArgument(0));
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
		FullscreenControllerTest.edt(() -> new RendererSuspender(manager, clientThread, client, 100)
			.suspend(windows::incrementAndGet, ex -> failed.countDown()));
		assertTrue(failed.await(2, TimeUnit.SECONDS));
		lateRead.get().run();
		FullscreenControllerTest.edt(() -> {});
		assertEquals(0, windows.get());
	}

	@Test
	public void rendererRestartedByRuneLiteDuringWaitIsStoppedBeforeWindowChange() throws Exception
	{
		Plugin gpu = mock(GpuPlugin.class);
		when(manager.getPlugins()).thenReturn(Collections.singletonList(gpu));
		when(manager.isPluginEnabled(gpu)).thenReturn(true);
		trackActive(gpu);
		java.util.concurrent.atomic.AtomicBoolean gl = new java.util.concurrent.atomic.AtomicBoolean(true);
		when(client.isGpu()).thenAnswer(call -> gl.get());
		AtomicReference<Runnable> clientRead = new AtomicReference<>();
		doAnswer(invocation ->
		{
			clientRead.set(invocation.getArgument(0));
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
		AtomicInteger windows = new AtomicInteger();
		RendererSuspender suspender = new RendererSuspender(manager, clientThread, client);
		FullscreenControllerTest.edt(() -> suspender.suspend(windows::incrementAndGet, ex -> fail(ex.toString())));
		readOnClientThread(clientRead);
		verify(manager, times(1)).stopPlugin(gpu);
		gl.set(false); // its teardown ran on the client thread

		// PluginManager.refreshPlugins (e.g. profile switch) restarts the enabled renderer;
		// its OpenGL setup is still pending, so it must not be stopped yet.
		manager.startPlugin(gpu);
		readOnClientThread(clientRead);
		assertEquals("Window must not change while a restarted renderer initializes", 0, windows.get());
		verify(manager, times(1)).stopPlugin(gpu);

		gl.set(true); // its setup finished: now it can be stopped safely
		readOnClientThread(clientRead);
		verify(manager, times(2)).stopPlugin(gpu);
		assertEquals(0, windows.get());

		gl.set(false);
		readOnClientThread(clientRead);
		assertEquals(1, windows.get());
		FullscreenControllerTest.edt(suspender::resume);
		assertTrue(manager.isPluginActive(gpu));
	}

	@Test
	public void rendererStillStartingIsNotStoppedUntilItHasStarted() throws Exception
	{
		// Fullscreen on startup while RuneLite is still starting the GPU plugin: its setup
		// is queued on the client thread, waiting for a valid canvas.
		Plugin gpu = mock(GpuPlugin.class);
		when(manager.getPlugins()).thenReturn(Collections.singletonList(gpu));
		when(manager.isPluginEnabled(gpu)).thenReturn(true);
		trackActive(gpu);
		java.util.concurrent.atomic.AtomicBoolean gl = new java.util.concurrent.atomic.AtomicBoolean(false);
		when(client.isGpu()).thenAnswer(call -> gl.get());
		AtomicReference<Runnable> clientRead = new AtomicReference<>();
		doAnswer(invocation ->
		{
			clientRead.set(invocation.getArgument(0));
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
		AtomicInteger windows = new AtomicInteger();
		RendererSuspender suspender = new RendererSuspender(manager, clientThread, client);
		FullscreenControllerTest.edt(() -> suspender.suspend(windows::incrementAndGet, ex -> fail(ex.toString())));
		verify(manager, never()).stopPlugin(gpu);
		readOnClientThread(clientRead);
		readOnClientThread(clientRead);
		verify(manager, never()).stopPlugin(gpu);
		assertEquals(0, windows.get());

		gl.set(true);
		readOnClientThread(clientRead);
		verify(manager).stopPlugin(gpu);
		gl.set(false);
		readOnClientThread(clientRead);
		assertEquals(1, windows.get());
		FullscreenControllerTest.edt(suspender::resume);
		verify(manager).startPlugin(gpu);
	}

	@Test
	public void rendererWhoseStartupFailedIsLeftAlone() throws Exception
	{
		// A GPU plugin that fails to start turns itself off; never restart it.
		Plugin gpu = mock(GpuPlugin.class);
		when(manager.getPlugins()).thenReturn(Collections.singletonList(gpu));
		when(manager.isPluginEnabled(gpu)).thenReturn(true);
		trackActive(gpu);
		when(client.isGpu()).thenReturn(false);
		AtomicReference<Runnable> clientRead = new AtomicReference<>();
		doAnswer(invocation ->
		{
			clientRead.set(invocation.getArgument(0));
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));
		AtomicInteger windows = new AtomicInteger();
		RendererSuspender suspender = new RendererSuspender(manager, clientThread, client);
		FullscreenControllerTest.edt(() -> suspender.suspend(windows::incrementAndGet, ex -> fail(ex.toString())));
		readOnClientThread(clientRead);
		manager.stopPlugin(gpu); // its own failure handling
		readOnClientThread(clientRead);
		assertEquals(1, windows.get());
		FullscreenControllerTest.edt(suspender::resume);
		verify(manager, never()).startPlugin(gpu);
	}

	@Test
	public void rendererThatNeverFinishesStartingTimesOutWithoutStoppingIt() throws Exception
	{
		Plugin gpu = mock(GpuPlugin.class);
		when(manager.getPlugins()).thenReturn(Collections.singletonList(gpu));
		when(manager.isPluginEnabled(gpu)).thenReturn(true);
		trackActive(gpu);
		when(client.isGpu()).thenReturn(false);
		CountDownLatch failed = new CountDownLatch(1);
		AtomicInteger windows = new AtomicInteger();
		FullscreenControllerTest.edt(() -> new RendererSuspender(manager, clientThread, client, 200)
			.suspend(windows::incrementAndGet, ex -> failed.countDown()));
		assertTrue(failed.await(2, TimeUnit.SECONDS));
		assertEquals(0, windows.get());
		verify(manager, never()).stopPlugin(gpu);
		verify(manager, never()).startPlugin(gpu);
	}

	/** Runs the pending client-thread read, then its EDT continuation. */
	private static void readOnClientThread(AtomicReference<Runnable> clientRead) throws Exception
	{
		Thread.sleep(120); // past the 50 ms retry delay
		FullscreenControllerTest.edt(() -> {});
		Runnable read = clientRead.getAndSet(null);
		assertNotNull("A client-thread read is pending", read);
		read.run();
		FullscreenControllerTest.edt(() -> {});
	}

	@Test
	public void userDisabledRendererIsNotRestarted() throws Exception
	{
		Plugin gpu = mock(GpuPlugin.class);
		when(manager.getPlugins()).thenReturn(Collections.singletonList(gpu));
		trackActive(gpu);
		when(manager.isPluginEnabled(gpu)).thenReturn(false);
		RendererSuspender suspender = new RendererSuspender(manager, clientThread, client);
		CountDownLatch done = new CountDownLatch(1);
		FullscreenControllerTest.edt(() -> suspender.suspend(() ->
		{
			suspender.resume();
			done.countDown();
		}, ex -> fail(ex.toString())));
		assertTrue(done.await(2, TimeUnit.SECONDS));
		verify(manager, never()).startPlugin(gpu);
	}

	@Test
	public void rendererRemovedFromActiveListBeforeFailedShutdownIsRecovered() throws Exception
	{
		Plugin gpu = mock(GpuPlugin.class);
		when(manager.getPlugins()).thenReturn(Collections.singletonList(gpu));
		when(client.isGpu()).thenReturn(true);
		when(manager.isPluginActive(gpu)).thenReturn(true);
		when(manager.isPluginEnabled(gpu)).thenReturn(true);
		when(manager.stopPlugin(gpu)).thenThrow(new PluginInstantiationException(new RuntimeException("Shutdown failed")));
		AtomicInteger errors = new AtomicInteger();
		AtomicInteger windows = new AtomicInteger();
		FullscreenControllerTest.edt(() -> new RendererSuspender(manager, clientThread, client)
			.suspend(windows::incrementAndGet, ex -> errors.incrementAndGet()));
		FullscreenControllerTest.edt(() -> {});
		assertEquals(0, windows.get());
		assertEquals(1, errors.get());
		verify(manager).startPlugin(gpu);
	}

	@Test
	public void restartFailureIsReportedAfterAttemptingAllStoppedRenderers() throws Exception
	{
		Plugin gpu = mock(GpuPlugin.class);
		Plugin hd = mock(Plugin.class);
		when(manager.getPlugins()).thenReturn(Arrays.asList(gpu, hd));
		when(manager.conflictsForPlugin(gpu)).thenReturn(Collections.singletonList(hd));
		trackActive(gpu, hd);
		when(manager.isPluginEnabled(any())).thenReturn(true);
		doThrow(new PluginInstantiationException(new RuntimeException("Restart failed"))).when(manager).startPlugin(gpu);
		RendererSuspender suspender = new RendererSuspender(manager, clientThread, client);
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<Exception> error = new AtomicReference<>();
		FullscreenControllerTest.edt(() -> suspender.suspend(() ->
		{
			try
			{
				suspender.resume();
			}
			catch (Exception ex)
			{
				error.set(ex);
			}
			done.countDown();
		}, ex -> fail(ex.toString())));
		assertTrue(done.await(2, TimeUnit.SECONDS));
		assertNotNull(error.get());
		assertTrue(error.get().getMessage().contains("Disable and re-enable"));
		assertEquals(1, error.get().getSuppressed().length);
		verify(manager).startPlugin(hd);
	}

	@Test
	public void failedStopAndFailedRecoveryStillCompleteFailureCallback() throws Exception
	{
		Plugin gpu = mock(GpuPlugin.class);
		when(manager.getPlugins()).thenReturn(Collections.singletonList(gpu));
		when(client.isGpu()).thenReturn(true);
		when(manager.isPluginActive(gpu)).thenReturn(true);
		when(manager.isPluginEnabled(gpu)).thenReturn(true);
		when(manager.stopPlugin(gpu)).thenThrow(new PluginInstantiationException(new RuntimeException("Stop failed")));
		when(manager.startPlugin(gpu)).thenThrow(new PluginInstantiationException(new RuntimeException("Restart failed")));
		AtomicReference<Exception> error = new AtomicReference<>();
		FullscreenControllerTest.edt(() -> new RendererSuspender(manager, clientThread, client)
			.suspend(() -> fail("Window must remain unchanged"), error::set));
		FullscreenControllerTest.edt(() -> {});
		assertNotNull(error.get());
		assertTrue(error.get().getMessage().contains("Stop failed"));
		assertTrue(error.get().getMessage().contains("Disable and re-enable"));
	}
}
