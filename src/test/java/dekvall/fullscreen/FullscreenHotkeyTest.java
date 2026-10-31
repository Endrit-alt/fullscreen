package dekvall.fullscreen;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.client.config.Keybind;
import net.runelite.client.util.HotkeyListener;
import org.junit.Test;
import static org.junit.Assert.*;

public class FullscreenHotkeyTest
{
	@Test
	public void rejectedToggleAndDialogFocusLossDoNotSwallowTyping() throws Exception
	{
		AtomicReference<HotkeyListener> listener = new AtomicReference<>();
		AtomicInteger calls = new AtomicInteger();
		Canvas canvas = new Canvas();
		FullscreenControllerTest.edt(() ->
		{
			listener.set(FullscreenPlugin.createHotkeyListener(
				() -> new Keybind(KeyEvent.VK_F11, 0), () ->
				{
					calls.incrementAndGet();
					// Models the custom-chrome error dialog taking focus.
					listener.get().focusLost();
				}));
			listener.get().keyPressed(key(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_F11));
			assertEquals("Window/dialog work must not run inside keyPressed", 0, calls.get());
		});
		FullscreenControllerTest.edt(() ->
		{
			assertEquals(1, calls.get());
			KeyEvent typed = key(canvas, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED);
			listener.get().keyTyped(typed);
			assertFalse("Bank search and chat must still receive typed characters", typed.isConsumed());
			listener.get().keyPressed(key(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_F11));
		});
		FullscreenControllerTest.edt(() ->
		{
			assertEquals("A second toggle must work without restarting RuneLite", 2, calls.get());
			KeyEvent typed = key(canvas, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED);
			listener.get().keyTyped(typed);
			assertFalse(typed.isConsumed());
		});
	}

	@Test
	public void ordinaryKeyReleaseRestoresTyping() throws Exception
	{
		Canvas canvas = new Canvas();
		FullscreenControllerTest.edt(() ->
		{
			HotkeyListener listener = FullscreenPlugin.createHotkeyListener(
				() -> new Keybind(KeyEvent.VK_F11, 0), () -> {});
			listener.keyPressed(key(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_F11));
			listener.keyReleased(key(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_F11));
			KeyEvent typed = key(canvas, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED);
			listener.keyTyped(typed);
			assertFalse(typed.isConsumed());
		});
	}

	private static KeyEvent key(Canvas canvas, int id, int code)
	{
		return new KeyEvent(canvas, id, System.currentTimeMillis(), 0, code,
			id == KeyEvent.KEY_TYPED ? 'a' : KeyEvent.CHAR_UNDEFINED)
		{
			@Override
			public int getExtendedKeyCode()
			{
				return code;
			}
		};
	}

	@Test
	public void rebindingOrDisablingHeldHotkeyDoesNotSwallowTyping() throws Exception
	{
		Canvas canvas = new Canvas();
		AtomicReference<Keybind> binding = new AtomicReference<>();
		HotkeyListener listener = FullscreenPlugin.createHotkeyListener(binding::get, () -> {});
		FullscreenControllerTest.edt(() ->
		{
			for (Keybind replacement : new Keybind[]{new Keybind(KeyEvent.VK_F12, 0), Keybind.NOT_SET})
			{
				binding.set(new Keybind(KeyEvent.VK_F11, 0));
				listener.keyPressed(key(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_F11));
				binding.set(replacement);
				listener.keyReleased(key(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_F11));
				KeyEvent typed = key(canvas, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED);
				listener.keyTyped(typed);
				assertFalse(typed.isConsumed());
			}
		});
	}

	@Test
	public void inputOffEdtFinishesBookkeepingBeforeDispatchingToggle() throws Exception
	{
		Canvas canvas = new Canvas();
		CountDownLatch beforeConsume = new CountDownLatch(1);
		CountDownLatch continuePress = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicReference<HotkeyListener> listener = new AtomicReference<>();
		listener.set(FullscreenPlugin.createHotkeyListener(() -> new Keybind(KeyEvent.VK_F11, 0), () ->
		{
			calls.incrementAndGet();
			listener.get().focusLost();
		}));
		KeyEvent press = new KeyEvent(canvas, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_F11, KeyEvent.CHAR_UNDEFINED)
		{
			@Override
			public int getExtendedKeyCode()
			{
				return KeyEvent.VK_F11;
			}

			@Override
			public int getKeyCode()
			{
				beforeConsume.countDown();
				try
				{
					if (!continuePress.await(2, TimeUnit.SECONDS))
					{
						throw new AssertionError("Test did not release the input handler");
					}
				}
				catch (InterruptedException ex)
				{
					throw new AssertionError(ex);
				}
				return KeyEvent.VK_F11;
			}
		};
		Thread input = new Thread(() ->
		{
			try
			{
				listener.get().keyPressed(press);
			}
			catch (Throwable ex)
			{
				failure.set(ex);
			}
		}, "fullscreen-test-input");
		input.start();
		try
		{
			assertTrue(beforeConsume.await(2, TimeUnit.SECONDS));
			FullscreenControllerTest.edt(() -> assertEquals(0, calls.get()));
		}
		finally
		{
			continuePress.countDown();
			input.join(2000);
		}
		assertFalse(input.isAlive());
		assertNull(failure.get());
		FullscreenControllerTest.edt(() ->
		{
			assertEquals(1, calls.get());
			KeyEvent typed = key(canvas, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED);
			listener.get().keyTyped(typed);
			assertFalse(typed.isConsumed());
		});
	}
}
