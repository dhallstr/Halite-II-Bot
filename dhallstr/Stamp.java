package dhallstr;

import hlt.*;

public class Stamp {
	public static String[] csuRam = {
			"                            ....0...                            ",
			"                       .0..............0.                       ",
			"                    .........      .........                    ",
			"                  ......                ......                  ",
			"                ..0..                      ..0..                ",
			"              .....                          .....              ",
			"             ....                              ....             ",
			"            ...                                  ...            ",
			"          .0..                                    ...           ",
			"         ....                                      .0.          ",
			"        ...                                         ...         ",
			"        0.     .0.                            .0.    ...        ",
			"       ... ..........                     ........... .0.       ",
			"      ..................                ..0...............      ",
			"     ....................              ....................     ",
			"     ......0...........0..           ..............0.......     ",
			"    .0......................        ......................0.    ",
			"    .............0...........      ..0......0...............    ",
			"   ..........   ..............    .............    ..........   ",
			"   ......          .......0...    ...........         .......   ",
			"  .....             .0........   .........0.             .....  ",
			"  ...0....           .........   ..0.......           ......0.  ",
			" ..........           ........    ........           .0.......  ",
			" ..........            .......    .......            .......... ",
			" ........0.             ..0..     ...0..             .......... ",
			" .0.......        0      ...        ...      0       ........0. ",
			" .........       ...                        ...       .0....... ",
			"..........       ...                         ..       ......... ",
			".......0.   0....0.                          0.....0  ..........",
			".........  .    ...                          ....   .. ..0......",
			".0....... ..         .0.               ..0.         .. .......0.",
			".........  ..       .. ..              .. ..       ..  .........",
			"......0..   .0...   .   ..            ..   .   ...0.   ..0......",
			".........     ....  0   ...          ..    0  ....     .........",
			".........      ..   ...  .0.        .0. ....   ..      .........",
			".0.......       0   .... ...        ... ....   0       ......0..",
			".......0.       ..  ........        ........   .      ..0...... ",
			" .........      ..  .0....0.        .0....0.  ..      ......... ",
			" ....   ..      ..  .......   .0..  ........  ..      ..   .... ",
			" .... ..         0   ......  .....   ......   0.        .. .... ",
			" .0..   .0       .   .....   ......  ......   ..      0.   ..0. ",
			" .....   ...     .   ..0..  .0....0.  ..0..   .     ...   ....  ",
			"  .....   ...    .    ...  .......... ....    .    ..    .....  ",
			"  ......    0.   .    ...  ..........  ..     .   .0    ......  ",
			"   0. ...    ..        .. ...0...0.... ..        ..    ... .0   ",
			"   .......    ..       .. ..        .. .        ..    ... ...   ",
			"    .. ....    ..         ...     ....         ..    ..0. ..    ",
			"    ... .0.    .0         .0...  ...0.        .0.   .... ...    ",
			"     0.  ...    ..         ..........         ..    ...  .0     ",
			"     ...  ...   ..           ..  ..           ..   .0.  ...     ",
			"      ...  .0    ..         .0....0.         ..    ..  ...      ",
			"       ...       0.          ......          0.       ...       ",
			"       ....     ...                          ..      ...        ",
			"        0...    ..                            ..    .0.         ",
			"         .......0                              0......          ",
			"          ....                                    ....          ",
			"           ....                                  ...            ",
			"             .0..                              ..0.             ",
			"              ....                           .....              ",
			"                .....                      .....                ",
			"                  ......                ......                  ",
			"                    .0.......      ........0                    ",
			"                      .....0.......0.....                       ",
			"                           ..........                           "

	};
	public static String[] csuTextTooClose = {
			".....................      ......................      .....              .....",
			".....................      ......................      .....              .....",
			"..0......0.......0...      ..0........0.......0..      ..0..              ..0..",
			".....................      ......................      .....              .....",
			".....................      ......................      .....              .....",
			".....                      .....                       .....              .....",
			".....                      .....                       .....              .....",
			".....                      .....                       .....              .....",
			".....                      .....                       .....              .....",
			".....                      ..0..                       .....              .....",
			"..0..                      .....                       ..0..              ..0..",
			".....                      .....                       .....              .....",
			".....                      .....                       .....              .....",
			".....                      ......................      .....              .....",
			".....                      ......................      .....              .....",
			".....                      ..0........0.......0..      .....              .....",
			".....                      ......................      .....              .....",
			".....                      ......................      .....              .....",
			"..0..                                       .....      ..0..              ..0..",
			".....                                       .....      .....              .....",
			".....                                       ..0..      .....              .....",
			".....                                       .....      .....              .....",
			".....                                       .....      .....              .....",
			".....                                       .....      .....              .....",
			".....................      ......................      ........................",
			".....................      ......................      ........................",
			"..0......0.......0...      ..0........0.......0..      ..0.........0........0..",
			".....................      ......................      ........................",
			".....................      ......................      ........................"
	};
	public static String[] csuText = {
			".....................                ......................                .....             .....",
			".....................                ......................                .....             .....",
			"..0.......0.......0..                ..0.......0........0..                ..0..             ..0..",
			".....................                ......................                .....             .....",
			".....................                ......................                .....             .....",
			".....                                .....                                 .....             .....",
			".....                                .....                                 .....             .....",
			".....                                .....                                 .....             .....",
			".....                                ..0..                                 .....             .....",
			".....                                .....                                 .....             .....",
			"..0..                                .....                                 ..0..             ..0..",
			".....                                .....                                 .....             .....",
			".....                                .....                                 .....             .....",
			".....                                ......................                .....             .....",
			".....                                ......................                .....             .....",
			".....                                ..0.......0........0..                .....             .....",
			".....                                ......................                .....             .....",
			".....                                ......................                .....             .....",
			"..0..                                                 .....                ..0..             ..0..",
			".....                                                 .....                .....             .....",
			".....                                                 .....                .....             .....",
			".....                                                 ..0..                .....             .....",
			".....                                                 .....                .....             .....",
			".....                                                 .....                .....             .....",
			".....................                ......................                .......................",
			".....................                ......................                .......................",
			"..0.......0.......0..                ..0.......0........0..                ..0........0........0..",
			".....................                ......................                .......................",
			".....................                ......................                ......................."
	
	};
	private GameMap gameMap;
	private String[] pattern;
	private int width = 0, height = 0;
	private Position location = null;
	private int count = -1;
	public Stamp(GameMap gameMap, String[] stamp) {
		this.gameMap = gameMap;
		pattern = stamp;
		height = stamp.length;
		for (int i = 0; i < height; i++) {
			if (stamp[i].length() > width) width = stamp[i].length();
		}
	}
	public int getWidth() {
		return width;
	}
	public int getHeight() {
		return height;
	}
	public int getNumShipsNeeded() {
		if (count != -1) return count;
		count = Stamp.getNumShipsNeeded(pattern);
		return count;
	}
	public static int getNumShipsNeeded(String[] stamp) {
		int count = 0;
		for (int i = 0; i < stamp.length; i++) {
			for (int j = 0; j < stamp[i].length(); j++) {
				if (stamp[i].charAt(j) == '0') count++;
			}
		}
		return count;
	}
	public Position findLocation() {
		// try to find a location near a bunch of our ships to make it faster to get into position
		for (Ship s: gameMap.getMyPlayer().getShips().values()) {
			if (locationClear(s)) {
				location = s;
				return s;
			}
			Position newLoc = new Position(s.getXPos() - width / 2, s.getYPos() - height / 2);
			if (locationClear(newLoc)) {
				location = newLoc;
				return newLoc;
			}
		}
		// At this point, we just have to try random spots.
		for (int i = 0; i < gameMap.getWidth() / 2; i += 20) {
			for (int j = 0; j < gameMap.getHeight() / 2; j += 20) {
				Position[] pos = {new Position(gameMap.getWidth() / 2 + i, gameMap.getHeight() / 2 + j),
						new Position(gameMap.getWidth() / 2 + i, gameMap.getHeight() / 2 - j),
						new Position(gameMap.getWidth() / 2 - i, gameMap.getHeight() / 2 + j),
						new Position(gameMap.getWidth() / 2 - i, gameMap.getHeight() / 2 - j)};
				for (Position position: pos) {
					if (locationClear(position)) {
						location = position;
						return position;
					}
				}
			}
		}
		// no possible spots were found. (this doesn't mean there isn't a spot, however)
		return null;
	}
	public Position[] getDataPoints() {
		if (location == null) return null;
		count = getNumShipsNeeded();
		Position[] data = new Position[count];
		int i = 0;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < pattern[y].length(); x++) {
				if (pattern[y].charAt(x) == '0') {
					data[i++] = new Position(location.getXPos() + x, location.getYPos() + y);
				}
			}
		}
		return data;
	}
	private boolean locationClear(Position point) {
		for (Planet p: gameMap.getAllPlanets().values()) {
			if (collides(point, p)) return false;
		}
		return true;
	}
	public boolean collides(Position point, Entity p) {
		if (point.getXPos() + width >= gameMap.getWidth() || point.getYPos() + height >= gameMap.getHeight()) return true;
		return (p.getXPos() >= -p.getRadius() + point.getXPos() && p.getXPos() <= p.getRadius() + point.getXPos() + width
			&& p.getYPos() >= -p.getRadius() + point.getYPos() && p.getYPos() <= p.getRadius() + point.getYPos() + height);
	}
	public boolean collides(Entity e) {
		if (e == null || location == null) return false;
		return collides(location, e);
	}
	public static void main(String[] args) {
		Stamp s = new Stamp(null, csuRam);
		System.out.println(s.getNumShipsNeeded());
	}
}
