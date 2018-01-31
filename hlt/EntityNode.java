package hlt;

public class EntityNode {
	private Entity e;
	private double dist;
	private EntityNode next;
	private int size = 1;// Note that size will only be accurate on the first EntityNode.
	public EntityNode(Entity e, double dist) {
		this.e = e;
		this.dist = dist;
		next = null;
	}
	public EntityNode(Entity e, double dist, EntityNode next) {
		this.e = e;
		this.dist = dist;
		this.next = next;
		if (next != null)
			size = 1 + next.size;
	}
	public Entity getEntity() {
		return e;
	}
	public double getDistance() {
		return dist;
	}
	public EntityNode getNext() {
		return next;
	}
	public void setNext(EntityNode n) {
		next = n;
		size += n.size;
	}
	public void setSize(int size) {
		this.size = size;
	}
	public int getSize() {
		return size;
	}
}
