package dhallstr;

public class LinkedList {
	public static class Node {
		final double value;
		Node next;
		public Node(double d) {
			this(d, null);
		}
		public Node(double d, Node n) {
			value = d;
			next = n;
		}
		public double getValue() {
			return value;
		}
		public Node getNext() {
			return next;
		}
	}
	
	public Node head = null;
	public Node tail = null;
	
	public void insert(double d) {
		if (head == null) {
			head = new Node(d);
			tail = head;
			return;
		}
		if (d < head.value) {
			head = new Node(d, head);
			return;
		}
		Node curr;
		for (curr = head; curr.next != null; curr = curr.next) {
			if (d < curr.next.value) break;
		}
		curr.next = new Node(d, curr.next);
		if (tail == curr) tail = curr.next;
	}
}
