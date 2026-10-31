package dekvall.fullscreen;

import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JPanel;
import net.runelite.client.ui.ContainableFrame;
import net.runelite.client.util.OSType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/**
 * Opt in with -PnativeTest=true; opens only short-lived test windows on the real desktop.
 * Uses plain AWT measurements only (no native code), like the plugin itself.
 */
public class WindowsFullscreenSmokeTest
{
	private ContainableFrame frame;
	private Canvas canvas;
	private JPanel sidebar;
	private FullscreenWindow window;

	@Before
	public void windowsOnly()
	{
		assumeTrue(Boolean.getBoolean("fullscreen.nativeTest"));
		assumeTrue(OSType.getOSType() == OSType.Windows && !GraphicsEnvironment.isHeadless());
	}

	@After
	public void close() throws Exception
	{
		if (frame != null)
		{
			FullscreenControllerTest.edt(() ->
			{
				try
				{
					if (window != null)
					{
						window.exit();
						window.detach();
					}
				}
				finally
				{
					frame.dispose();
				}
			});
		}
	}

	@Test
	public void borderlessCoversMonitorAndRestoresWindow() throws Exception
	{
		exercise(false, false);
	}

	@Test
	public void windowMaximizedBeforeFullscreenRestoresMaximizedAndItsNormalSize() throws Exception
	{
		exercise(true, false);
	}

	@Test
	public void alwaysOnTopAndResizeLockArePreserved() throws Exception
	{
		exercise(false, true);
	}

	@Test
	public void repeatedMonitorChangesKeepCanvasAligned() throws Exception
	{
		open();
		Rectangle original = bounds();
		AtomicReference<GraphicsConfiguration> target = new AtomicReference<>(frame.getGraphicsConfiguration());
		FullscreenControllerTest.edt(() ->
		{
			window = new FullscreenWindow(frame, target::get);
			window.validate(Mode.BORDERLESS);
			window.enter(Mode.BORDERLESS);
		});
		waitForEvents();
		GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
		for (int iteration = 0; iteration < 6; iteration++)
		{
			target.set(devices[(iteration / 2) % devices.length].getDefaultConfiguration());
			FullscreenControllerTest.edt(() ->
			{
				window.validate(Mode.BORDERLESS);
				assertTrue(window.moveToMonitor(Mode.BORDERLESS));
			});
			waitForEvents();
			assertCoversMonitor(target.get().getBounds(), "iteration " + iteration);
		}
		FullscreenControllerTest.edt(window::exit);
		waitForEvents();
		assertEquals(original, bounds());
		assertFalse(frame.isUndecorated());
	}

	@Test
	public void exclusiveCoversMonitorAndRestoresWindow() throws Exception
	{
		open();
		Rectangle original = bounds();
		GraphicsDevice device = frame.getGraphicsConfiguration().getDevice();
		FullscreenControllerTest.edt(() ->
		{
			window = new FullscreenWindow(frame);
			window.validate(Mode.EXCLUSIVE);
			window.enter(Mode.EXCLUSIVE);
		});
		waitForEvents();
		assertSame(frame, device.getFullScreenWindow());
		assertEquals(device.getDefaultConfiguration().getBounds(), bounds());
		FullscreenControllerTest.edt(window::exit);
		waitForEvents();
		assertNull(device.getFullScreenWindow());
		assertEquals(original, bounds());
	}

	private void exercise(boolean maximized, boolean topmostAndLocked) throws Exception
	{
		open();
		Rectangle normal = bounds();
		FullscreenControllerTest.edt(() ->
		{
			frame.setAlwaysOnTop(topmostAndLocked);
			frame.setResizable(!topmostAndLocked);
			if (maximized)
			{
				frame.setExtendedState(Frame.MAXIMIZED_BOTH);
			}
		});
		waitForEvents();
		Rectangle monitor = frame.getGraphicsConfiguration().getBounds();
		FullscreenControllerTest.edt(() ->
		{
			// Created after maximizing, like a RuneLite session that started maximized.
			window = new FullscreenWindow(frame);
			window.validate(Mode.BORDERLESS);
			window.enter(Mode.BORDERLESS);
		});
		waitForEvents();
		assertTrue(frame.isUndecorated());
		assertCoversMonitor(monitor, maximized ? "maximized" : "normal");
		assertEquals(topmostAndLocked, frame.isAlwaysOnTop());

		// Simulate sidebar expansion attempting to resize outside this monitor.
		FullscreenControllerTest.edt(() -> frame.setSize(frame.getWidth() + 250, frame.getHeight() - 100));
		waitForEvents();
		assertCoversMonitor(monitor, "after sidebar resize");

		FullscreenControllerTest.edt(window::exit);
		waitForEvents();
		assertFalse(frame.isUndecorated());
		assertEquals(topmostAndLocked, frame.isAlwaysOnTop());
		assertEquals(!topmostAndLocked, frame.isResizable());
		if (maximized)
		{
			assertEquals(Frame.MAXIMIZED_BOTH, frame.getExtendedState() & Frame.MAXIMIZED_BOTH);
			FullscreenControllerTest.edt(() -> frame.setExtendedState(Frame.NORMAL));
			waitForEvents();
		}
		assertEquals("Normal window size and position", normal, bounds());
	}

	private void open() throws Exception
	{
		FullscreenControllerTest.edt(() ->
		{
			frame = new ContainableFrame();
			frame.setTitle("Fullscreen plugin native test - closes automatically");
			JPanel content = new JPanel(new BorderLayout());
			canvas = new Canvas();
			canvas.setPreferredSize(new Dimension(765, 503));
			sidebar = new JPanel();
			sidebar.setPreferredSize(new Dimension(242, 503));
			content.add(canvas, BorderLayout.CENTER);
			content.add(sidebar, BorderLayout.EAST);
			frame.setContentPane(content);
			frame.pack();
			frame.setLocation(120, 100);
			frame.setVisible(true);
		});
		waitForEvents();
	}

	private void assertCoversMonitor(Rectangle monitor, String when) throws Exception
	{
		FullscreenControllerTest.edt(() ->
		{
			assertEquals("Window covers the monitor (" + when + ")", monitor, frame.getBounds());
			Point origin = canvas.getLocationOnScreen();
			Rectangle game = new Rectangle(origin, canvas.getSize());
			assertEquals("Canvas top-left (" + when + ")", monitor.getLocation(), game.getLocation());
			assertEquals("Canvas height (" + when + ")", monitor.height, game.height);
			assertEquals("Canvas beside sidebar (" + when + ")", monitor.width - sidebar.getWidth(), game.width);
		});
	}

	private Rectangle bounds() throws Exception
	{
		AtomicReference<Rectangle> result = new AtomicReference<>();
		FullscreenControllerTest.edt(() -> result.set(frame.getBounds()));
		return result.get();
	}

	private static void waitForEvents() throws Exception
	{
		// Native window messages are delivered asynchronously to AWT.
		java.util.concurrent.locks.LockSupport.parkNanos(500_000_000L);
		FullscreenControllerTest.edt(() -> {});
		java.util.concurrent.locks.LockSupport.parkNanos(200_000_000L);
		FullscreenControllerTest.edt(() -> {});
	}
}
