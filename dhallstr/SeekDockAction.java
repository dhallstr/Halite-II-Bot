package dhallstr;

import hlt.*;

public class SeekDockAction implements PastAction {
	int shipId, planetId;
	public SeekDockAction(Ship ship, Planet planet) {
		shipId = ship.getId();
		planetId = planet.getId();
	}
	@Override
	public int getShipId() {
		return shipId;
	}
	public int getPlanetId() {
		return planetId;
	}
	
}
