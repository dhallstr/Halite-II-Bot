package dhallstr;

public class LureAction implements PastAction {
	private int shipId;
	private int enemyId;
	private int enemy;
	public LureAction(int shipId, int enemyId, int enemy) {
		this.shipId = shipId;
		this.enemyId = enemyId;
		this.enemy = enemy;
	}
	@Override
	public int getShipId() {
		return shipId;
	}
	public int getEnemyId() {
		return enemyId;
	}
	public int getEnemyPlayer() {
		return enemy;
	}
}
