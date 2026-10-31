package dekvall.fullscreen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class FullscreenControllerTest
{
	private FullscreenController.Window window;
	private FakeRenderers renderers;
	private FullscreenController controller;
	private List<Exception> errors;

	@Before
	public void setUp()
	{
		window = mock(FullscreenController.Window.class);
		renderers = new FakeRenderers();
		errors = new ArrayList<>();
		controller = new FullscreenController(window, renderers, () -> {}, errors::add);
	}

	@Test
	public void windowsBorderlessDoesNotStopRenderers() throws Exception
	{
		edt(() -> controller.toggle(Mode.BORDERLESS));
		assertTrue(controller.isActive());
		assertFalse(renderers.suspended);
		verify(window).enter(Mode.BORDERLESS);
		edt(controller::close);
		verify(window).exit();
		assertFalse(controller.isActive());
	}

	@Test
	public void repeatedRequestsDoNotOverwriteSavedState() throws Exception
	{
		when(window.needsRendererRestart(any())).thenReturn(true);
		edt(() ->
		{
			controller.toggle(Mode.BORDERLESS);
			controller.toggle(Mode.EXCLUSIVE);
		});
		assertTrue(controller.isTransitioning());
		verify(window, never()).enter(any());
		edt(renderers.ready);
		verify(window).enter(Mode.BORDERLESS);
		assertEquals(1, renderers.resumes);
	}

	@Test
	public void shutdownDuringEntryCancelsWindowChangeAndRestoresRenderer() throws Exception
	{
		when(window.needsRendererRestart(any())).thenReturn(true);
		edt(() ->
		{
			controller.toggle(Mode.BORDERLESS);
			controller.close();
			renderers.ready.run();
		});
		assertFalse(controller.isActive());
		assertFalse(controller.isTransitioning());
		verify(window, never()).enter(any());
		assertEquals(1, renderers.resumes);
	}

	@Test
	public void shutdownDuringExitDoesNotReenter() throws Exception
	{
		edt(() -> controller.toggle(Mode.BORDERLESS));
		when(window.needsRendererRestart(any())).thenReturn(true);
		edt(() ->
		{
			controller.toggle(Mode.BORDERLESS);
			controller.close();
			renderers.ready.run();
		});
		verify(window, times(1)).enter(any());
		verify(window).exit();
		assertFalse(controller.isActive());
	}

	@Test
	public void failedEntryRollsBackAndResumesRenderer() throws Exception
	{
		when(window.needsRendererRestart(any())).thenReturn(true);
		doThrow(new IllegalStateException("display failed")).when(window).enter(any());
		edt(() ->
		{
			controller.toggle(Mode.BORDERLESS);
			renderers.ready.run();
		});
		verify(window).exit();
		assertEquals(1, errors.size());
		assertEquals(1, renderers.resumes);
		assertFalse(controller.isActive());
		assertFalse(controller.isTransitioning());
	}

	@Test
	public void rendererTimeoutLeavesWindowUntouched() throws Exception
	{
		when(window.needsRendererRestart(any())).thenReturn(true);
		edt(() ->
		{
			controller.toggle(Mode.EXCLUSIVE);
			renderers.failed.accept(new IllegalStateException("timeout"));
		});
		verify(window, never()).enter(any());
		assertFalse(controller.isTransitioning());
		assertEquals(1, errors.size());
	}

	@Test
	public void modeChangeExitsOldModeBeforeEnteringNewMode() throws Exception
	{
		edt(() ->
		{
			controller.toggle(Mode.BORDERLESS);
			controller.changeMode(Mode.EXCLUSIVE);
		});
		org.mockito.InOrder order = inOrder(window);
		order.verify(window).enter(Mode.BORDERLESS);
		order.verify(window).exit();
		order.verify(window).enter(Mode.EXCLUSIVE);
		assertTrue(controller.isActive());
	}

	@Test
	public void changesOffEdtAreRejected()
	{
		assertThrows(IllegalStateException.class, () -> controller.toggle(Mode.BORDERLESS));
	}

	@Test
	public void configChangeDuringEntryIsAppliedAfterGpuRelease() throws Exception
	{
		when(window.needsRendererRestart(any())).thenReturn(true);
		edt(() ->
		{
			controller.toggle(Mode.BORDERLESS);
			controller.changeMode(Mode.EXCLUSIVE);
			renderers.ready.run();
			renderers.ready.run();
		});
		org.mockito.InOrder order = inOrder(window);
		order.verify(window).enter(Mode.BORDERLESS);
		order.verify(window).exit();
		order.verify(window).enter(Mode.EXCLUSIVE);
		assertFalse(controller.isTransitioning());
	}

	@Test
	public void shutdownAfterTimeoutCanRetryWithoutLosingSnapshot() throws Exception
	{
		edt(() -> controller.toggle(Mode.BORDERLESS));
		when(window.needsRendererRestart(any())).thenReturn(true);
		edt(() ->
		{
			controller.close();
			renderers.failed.accept(new IllegalStateException("timeout"));
			assertTrue(controller.isActive());
			controller.close();
			renderers.ready.run();
		});
		verify(window).exit();
		assertFalse(controller.isActive());
	}

	static void edt(Runnable action) throws Exception
	{
		SwingUtilities.invokeAndWait(action);
	}

	@Test
	public void unsupportedModeChangeKeepsWorkingFullscreenAndRenderer() throws Exception
	{
		edt(() -> controller.toggle(Mode.BORDERLESS));
		doThrow(new IllegalStateException("Unsupported mode")).when(window).validate(Mode.EXCLUSIVE);
		edt(() -> controller.changeMode(Mode.EXCLUSIVE));
		assertTrue(controller.isActive());
		assertFalse(controller.isTransitioning());
		assertFalse(renderers.suspended);
		verify(window, never()).exit();
		verify(window, never()).enter(Mode.EXCLUSIVE);
		assertEquals(1, errors.size());
	}

	@Test
	public void partialEntryAndFailedRollbackRemainRecoverable() throws Exception
	{
		doThrow(new IllegalStateException("Entry failed")).when(window).enter(Mode.BORDERLESS);
		doThrow(new IllegalStateException("Restore failed")).doNothing().when(window).exit();
		edt(() -> controller.toggle(Mode.BORDERLESS));
		assertTrue("A partly changed window still needs an exit control", controller.isActive());
		assertEquals(1, errors.get(0).getSuppressed().length);
		edt(() -> controller.toggle(Mode.BORDERLESS));
		assertFalse(controller.isActive());
		verify(window, times(1)).enter(Mode.BORDERLESS);
		verify(window, times(2)).exit();
	}

	@Test
	public void queuedUnsupportedModeRestoresControlsAfterEntry() throws Exception
	{
		java.util.concurrent.atomic.AtomicInteger refreshes = new java.util.concurrent.atomic.AtomicInteger();
		controller = new FullscreenController(window, renderers, refreshes::incrementAndGet, errors::add);
		when(window.needsRendererRestart(any())).thenReturn(true);
		doThrow(new IllegalStateException("Unsupported mode")).when(window).validate(Mode.EXCLUSIVE);
		edt(() ->
		{
			controller.toggle(Mode.BORDERLESS);
			controller.changeMode(Mode.EXCLUSIVE);
			renderers.ready.run();
		});
		assertEquals("Controls must be refreshed after rejecting the queued mode", 2, refreshes.get());
		assertTrue(controller.isActive());
		assertFalse(controller.isTransitioning());
		verify(window, never()).exit();
	}

	@Test
	public void rendererRestartFailureIsReportedAndControlsRemainUsable() throws Exception
	{
		when(window.needsRendererRestart(any())).thenReturn(true);
		FullscreenController.Renderers broken = mock(FullscreenController.Renderers.class);
		java.util.concurrent.atomic.AtomicReference<Runnable> ready = new java.util.concurrent.atomic.AtomicReference<>();
		doAnswer(call ->
		{
			ready.set(call.getArgument(0));
			return null;
		}).when(broken).suspend(any(), any());
		doThrow(new IllegalStateException("GPU restart failed")).when(broken).resume();
		controller = new FullscreenController(window, broken, () -> {}, errors::add);
		edt(() ->
		{
			controller.toggle(Mode.BORDERLESS);
			controller.changeMode(Mode.EXCLUSIVE);
			ready.get().run();
		});
		assertEquals(1, errors.size());
		assertEquals("GPU restart failed", errors.get(0).getMessage());
		assertTrue(controller.isActive());
		assertFalse(controller.isTransitioning());
		verify(window, never()).enter(Mode.EXCLUSIVE);
		edt(controller::close);
		edt(() -> ready.get().run());
		assertFalse(controller.isActive());
	}

	@Test
	public void monitorChangeReentersSameModeWithoutLosingExitState() throws Exception
	{
		edt(() ->
		{
			controller.toggle(Mode.BORDERLESS);
			controller.changeMonitor();
		});
		org.mockito.InOrder order = inOrder(window);
		order.verify(window).enter(Mode.BORDERLESS);
		order.verify(window).exit();
		order.verify(window).enter(Mode.BORDERLESS);
		assertTrue(controller.isActive());
		assertFalse(controller.isTransitioning());
	}

	@Test
	public void windowedSelectionRestoresWindow() throws Exception
	{
		edt(() ->
		{
			controller.toggle(Mode.BORDERLESS);
			controller.changeMode(Mode.WINDOWED);
		});
		verify(window).exit();
		verify(window, never()).enter(Mode.WINDOWED);
		assertFalse(controller.isActive());
	}

	@Test
	public void windowedSelectionCancelsPendingEntry() throws Exception
	{
		when(window.needsRendererRestart(any())).thenReturn(true);
		edt(() ->
		{
			controller.toggle(Mode.EXCLUSIVE);
			controller.changeMode(Mode.WINDOWED);
			renderers.ready.run();
		});
		verify(window, never()).enter(any());
		assertEquals(1, renderers.resumes);
		assertFalse(controller.isActive());
		assertFalse(controller.isTransitioning());
	}

	@Test
	public void latestSelectionOverridesQueuedWindowed() throws Exception
	{
		when(window.needsRendererRestart(any())).thenReturn(true);
		edt(() ->
		{
			controller.toggle(Mode.EXCLUSIVE);
			controller.changeMode(Mode.WINDOWED);
			controller.changeMode(Mode.BORDERLESS);
			renderers.ready.run();
			renderers.ready.run();
		});
		verify(window).enter(Mode.BORDERLESS);
		verify(window, never()).enter(Mode.WINDOWED);
		assertTrue(controller.isActive());
	}

	@Test
	public void supportedMonitorMoveKeepsFullscreenAndOriginalWindowSnapshot() throws Exception
	{
		when(window.moveToMonitor(Mode.BORDERLESS)).thenReturn(true);
		edt(() ->
		{
			controller.toggle(Mode.BORDERLESS);
			controller.changeMonitor();
			controller.changeMonitor();
		});
		verify(window, times(1)).enter(Mode.BORDERLESS);
		verify(window, times(2)).moveToMonitor(Mode.BORDERLESS);
		verify(window, never()).exit();
		assertTrue(controller.isActive());
		assertFalse(renderers.suspended);
		edt(controller::close);
		verify(window).exit();
		assertFalse(controller.isActive());
	}

	@Test
	public void selectingFullscreenModeEntersFromWindowedWithoutToggle() throws Exception
	{
		edt(() -> controller.changeMode(Mode.BORDERLESS));
		verify(window).enter(Mode.BORDERLESS);
		assertEquals(Mode.BORDERLESS, controller.currentMode());
		edt(() -> controller.changeMode(Mode.WINDOWED));
		verify(window).exit();
		assertEquals(Mode.WINDOWED, controller.currentMode());
	}

	@Test
	public void selectionDuringPendingExitEntersLatestMode() throws Exception
	{
		edt(() -> controller.changeMode(Mode.BORDERLESS));
		when(window.needsRendererRestart(any())).thenReturn(true);
		edt(() ->
		{
			controller.changeMode(Mode.WINDOWED);
			controller.changeMode(Mode.EXCLUSIVE);
			renderers.ready.run();
			renderers.ready.run();
		});
		assertEquals(Mode.EXCLUSIVE, controller.currentMode());
		verify(window).enter(Mode.EXCLUSIVE);
		assertFalse(controller.isTransitioning());
	}

	@Test
	public void choosingMonitorWhileWindowedDoesNotEnterFullscreen() throws Exception
	{
		edt(() -> controller.changeMonitor());
		verifyNoInteractions(window);
		assertEquals(Mode.WINDOWED, controller.currentMode());
	}

	private static final class FakeRenderers implements FullscreenController.Renderers
	{
		private boolean suspended;
		private int resumes;
		private Runnable ready;
		private Consumer<Exception> failed;

		@Override
		public void suspend(Runnable ready, Consumer<Exception> failed)
		{
			suspended = true;
			this.ready = ready;
			this.failed = failed;
		}

		@Override
		public void resume()
		{
			resumes++;
		}
	}
}
