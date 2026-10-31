package dekvall.fullscreen;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class MonitorSelectorTest
{
	private GraphicsDevice primary;
	private GraphicsDevice secondary;
	private GraphicsConfiguration primaryConfiguration;
	private GraphicsConfiguration secondaryConfiguration;

	@Before
	public void setUp()
	{
		primary = mock(GraphicsDevice.class);
		secondary = mock(GraphicsDevice.class);
		primaryConfiguration = mock(GraphicsConfiguration.class);
		secondaryConfiguration = mock(GraphicsConfiguration.class);
		when(primary.getIDstring()).thenReturn("primary-id");
		when(secondary.getIDstring()).thenReturn("secondary-id");
		when(primary.getDefaultConfiguration()).thenReturn(primaryConfiguration);
		when(secondary.getDefaultConfiguration()).thenReturn(secondaryConfiguration);
		when(primaryConfiguration.getBounds()).thenReturn(new Rectangle(0, 0, 1920, 1080));
		when(secondaryConfiguration.getBounds()).thenReturn(new Rectangle(-2560, -200, 2560, 1440));
	}

	@Test
	public void currentAndPrimaryMonitorChoicesAreDistinct()
	{
		GraphicsDevice[] devices = {primary, secondary};
		assertSame(secondaryConfiguration, MonitorSelector.select(secondaryConfiguration,
			MonitorTarget.CURRENT, "", devices, primary));
		assertSame(primaryConfiguration, MonitorSelector.select(secondaryConfiguration,
			MonitorTarget.PRIMARY, "", devices, primary));
	}

	@Test
	public void selectedDeviceUsesSavedIdentifierEvenIfOrderChanges()
	{
		assertSame(secondaryConfiguration, MonitorSelector.select(primaryConfiguration,
			MonitorTarget.SELECTED, "secondary-id", new GraphicsDevice[]{secondary, primary}, primary));
	}

	@Test
	public void disconnectedOrUnsetPreferenceFallsBackToCurrentMonitor()
	{
		assertSame(primaryConfiguration, MonitorSelector.select(primaryConfiguration,
			MonitorTarget.SELECTED, "secondary-id", new GraphicsDevice[]{primary}, primary));
		assertSame(primaryConfiguration, MonitorSelector.select(primaryConfiguration,
			MonitorTarget.SELECTED, "", new GraphicsDevice[]{primary, secondary}, primary));
	}

	@Test
	public void chooserIncludesEveryDisplayWithResolutionAndOrigin()
	{
		List<MonitorSelector.Choice> choices = MonitorSelector.choices(new GraphicsDevice[]{primary, secondary}, primary);
		assertEquals(5, choices.size());
		assertTrue(choices.get(2).matches(MonitorTarget.SECONDARY, ""));
		assertTrue(choices.get(3).toString().contains("primary"));
		assertTrue(choices.get(4).toString().contains("2560 × 1440 at -2560, -200"));
		assertTrue(choices.get(4).matches(MonitorTarget.SELECTED, "secondary-id"));
	}

	@Test
	public void secondaryFindsNonPrimaryRegardlessOfDeviceOrder()
	{
		assertSame(secondaryConfiguration, MonitorSelector.select(primaryConfiguration,
			MonitorTarget.SECONDARY, "", new GraphicsDevice[]{primary, secondary}, primary));
		assertSame(secondaryConfiguration, MonitorSelector.select(primaryConfiguration,
			MonitorTarget.SECONDARY, "", new GraphicsDevice[]{secondary, primary}, primary));
	}

	@Test
	public void absentSecondaryFallsBackToCurrentDisplay()
	{
		assertSame(primaryConfiguration, MonitorSelector.select(primaryConfiguration,
			MonitorTarget.SECONDARY, "", new GraphicsDevice[]{primary}, primary));
	}
}
