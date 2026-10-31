package dekvall.fullscreen;

public enum Mode
{
	BORDERLESS("Borderless Window"),
	EXCLUSIVE("Exclusive Fullscreen"),
	WINDOWED("Windowed");

	private final String label;

	Mode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
