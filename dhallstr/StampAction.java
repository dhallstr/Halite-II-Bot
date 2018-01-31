package dhallstr;

import hlt.*;

public class StampAction implements PastAction {
	public boolean finished = false;
	private int shipId;
	private Position goalPosition;
	public StampAction(Ship ship, Position goal) {
		shipId = ship.getId();
		goalPosition = goal;
	}
	public Entity getGoal() {
		return new Entity(-1, -1, goalPosition.getXPos(), goalPosition.getYPos(), 255, 0.5);
	}
	@Override
	public int getShipId() {
		return shipId;
	}
}
