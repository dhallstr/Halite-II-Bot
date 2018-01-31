import hlt.*;
import hlt.Ship.DockingStatus;
import dhallstr.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.*;

public class MyBot {
	public static final String BOT_NAME = "Broken Heart";
	
	public static final int PLANET_TOO_FAR = 230, LOCK_ON_SIGHT = 50, PILLAGE_SIGHT = 40, CHALLENGER_SIGHT = 20,
			SUPPORTER_SIGHT = 20, AGGRESSION_SIGHT = 40, DISTRESS_DISTANCE_DOCKED = 20,
			DISTRESS_SIGHT = 20, AUTO_RUSH2P = 100, NUM_SHIPS_BEFORE_HIDE = 20, ENEMY_PLANET_CLOSE_DIST = 15,
			SQUAD_NEARBY = 17, NUM_CORRECTIONS = 36, RUSH4P_DIST = 70, GOOD_PLANET_SCORE_4P = 580;
	public static final double FUDGE_FACTOR = 0.51, WEAPON_RADIUS = 5.999,
			ANGLE_DELTA = Math.PI / 36.0;
	public static double LOCK_ON_GOOD_PLANET_SCORE = 540;
	public static boolean DEBUG = false;
	public static int DEBUG_FRAME = 99, DEBUG_SHIP = -1;
	public static boolean debugThisFrame = false;

	Position[] csuData = null;
	int csuDataI = 0;
	boolean csuMode = false, csuModeAllAtTarget = true;

	private boolean isTwoPlayer = false, rush2p = false, totalAvoidMode = false, rush4p = false, noAggression = false,
			rushRetreat = false, rushAdvance = false;
	private boolean clearedForDock = false, allowClearDock = false;// protection against rushes
	private int noDockShip = -1, hideyShip = -1, canTryOutDock = -1, startPillageDistance = 80, rush4pTarget = -1;
	private int rush2pAngle = -1, rush2pThrust = -1;
	private Ship rush2pTarget = null;
	private GameMap gameMap;
	private ArrayList<Move> moveList;
	private ArrayList<DistressedShip> distressList;
	private ArrayList<Position> coordAttack;
	private TreeMap<Integer, Integer> numGoing;
	private ArrayList<MovingShip> movingShips;
	private TreeMap<Integer, Boolean> movedShip, didShipsTurn, inMoveQueue, willDock;
	private TreeMap<Integer, Integer> shipsCanFollow = new TreeMap<>();
	private TreeMap<Integer, Integer> shipInd = new TreeMap<>();
	private ArrayList<SquadLeader> squads;
	private ArrayList<PastAction> pastActions;
	private EntityNode sortedEntities = null;
	private PrintWriter log;
	private Position averagePlanetPos = null;

	private long startTime = 0;
	private int myShipsCount = 0;
	private double numPillaging = 0, undockedShips = 0;
	private int turnNum = 0;
	
	private Random rand = new Random(234643);

	// TODO optimization: isProtected() and wouldDieNextTurn() can both be
	//      calculated only once per turn
	// TODO MORE IDEAS
	/*      if sending a ship to join a big group that's at stalemate, dock to a planet instead.
	 * */
	public static void main(final String[] args) {
		try {
			new MyBot();
		} catch (Exception e) {
			System.out.println("Exception! | " + e.getMessage() + " | " + Arrays.toString(e.getStackTrace()));
			throw e;
		}
	}

	public MyBot() {
		if (DEBUG) {
			try {
				log = new PrintWriter(new File("logfile-" + BOT_NAME + ".log"));
				logEntry("Started DEBUG on " + BOT_NAME);
			} catch (FileNotFoundException e) {
				System.out.println("Could not open 'logfile.log'!");
				System.exit(1);
			}
		}
		final Networking networking = new Networking();
		gameMap = networking.initialize(BOT_NAME);

		init();
		for (turnNum = 0; true; turnNum++) {
			nextTurn();
			Collection<Ship> leaders = getLeaders();
			Collection<Ship> ships = gameMap.getMyPlayer().getShips().values();
			

			startTime = System.currentTimeMillis();
			numPillaging = 0;
			undockedShips = countMyUndocked(ships);
			int enemyPlanets = countEnemyPlanets();
			boolean enemyOwnsPlanet = enemyPlanets > 0;
			rush2p = checkDistressCalls(ships, enemyOwnsPlanet);
			rush2p = shouldRush2pMidgame(enemyOwnsPlanet);
			
			moveShips(leaders);
			moveShips(ships);
			Networking.sendMoves(moveList);
		}
	}

	private void init() {
		isTwoPlayer = gameMap.getAllPlayers().size() == 2;
		if (isTwoPlayer) {
			startPillageDistance = 101;
		}
		else {
			LOCK_ON_GOOD_PLANET_SCORE = GOOD_PLANET_SCORE_4P;
		}
		rush2p = shouldRush2p();
		noDockShip = isTwoPlayer ? -1 : -2;
		if (isTwoPlayer) {
			Planet closestPlanet = null;
			double closestDist = Double.MAX_VALUE;
			for (Ship ship : gameMap.getAllShips()) {
				if (ship.getOwner() != gameMap.getMyPlayerId()) {
					for (Planet p : gameMap.getAllPlanets().values()) {
						double dist = ship.getDistanceTo(p);
						if (dist < closestDist) {
							closestDist = dist;
							closestPlanet = p;
						}
					}
					break;
				}
			}
			if (closestPlanet != null) {
				rush2p = shouldRush2p(closestPlanet);
				for (Ship s : gameMap.getAllShips()) {
					if (s.getOwner() == gameMap.getMyPlayerId()) {
						if (s.getDistanceTo(closestPlanet) > startPillageDistance)
							noDockShip = -2;
						break;
					}
				}
			}
		}
		else {
			Ship myFirstShip = (Ship)gameMap.getMyPlayer().getShips().values().toArray()[0];
			double minDist = Double.MAX_VALUE;
			Ship target = null;
			for (Player p: gameMap.getAllPlayers()) {
				if (p.getId() == gameMap.getMyPlayerId()) continue;
				if (p.getShips().values().isEmpty()) continue;
				Ship first = (Ship)p.getShips().values().toArray()[0];
				double dist = myFirstShip.getDistanceTo(first);
				if (dist < minDist) {
					minDist = dist;
					target = first;
				}
			}
			if (target != null) {
				Planet closestPlanet = null;
				minDist = Double.MAX_VALUE;
				for (Planet p: gameMap.getAllPlanets().values()) {
					double dist = p.getDistanceTo(target);
					if (dist < minDist) {
						minDist = dist;
						closestPlanet = p;
					}
				}
				if (closestPlanet != null && myFirstShip.getDistanceTo(closestPlanet) < RUSH4P_DIST) {
					rush4p = true;
					rush4pTarget = target.getOwner();
				}
			}
		}
		hideyShip = isTwoPlayer ? -2 : -1;// -2 means don't hide any, -1 means
											// hide when you find a good
											// candidate.
		moveList = new ArrayList<>();
		distressList = new ArrayList<>();
		numGoing = new TreeMap<>();
		movingShips = new ArrayList<>();
		movedShip = new TreeMap<>();
		didShipsTurn = new TreeMap<>();
		inMoveQueue = new TreeMap<>();
		willDock = new TreeMap<>();
		squads = new ArrayList<>();
		pastActions = new ArrayList<>();
		coordAttack = new ArrayList<>();
	}

	private void nextTurn() {
		logEntry("next turn");
		moveList.clear();
		distressList.clear();
		numGoing.clear();
		movingShips.clear();
		movedShip.clear();
		didShipsTurn.clear();
		inMoveQueue.clear();
		coordAttack.clear();
		willDock.clear();
		for (int i = squads.size() - 1; i >= 0; i--) {
			squads.get(i).nextTurn();
			if (gameMap.getShip(gameMap.getMyPlayerId(), squads.get(i).shipId) == null) {
				squads.remove(i);
			}
		}
		if (hideyShip >= 0 && gameMap.getShip(gameMap.getMyPlayerId(), hideyShip) == null) {
			hideyShip = -1;
		}
		shipsCanFollow.clear();
		shipInd.clear();
		myShipsCount = gameMap.getMyPlayer().getShips().values().size();
		gameMap.updateMap(Networking.readLineIntoMetadata());
		if (!clearedForDock) {
			allowClearDock = false;
			canTryOutDock = -1;
			// 5 turns for docking + 12 for producing a ship = 17 turns
			// 17 turns times max thrust of 7 = 119 units
			// but using 100 just cause
			int totalEnemies = 0;// this is expected to be multiplied by the number of my ships (i.e. 3)
			double leastDist = Double.MAX_VALUE, farthestDist = Double.MIN_VALUE;
			int farthestId = -1;
			int numShipsUndocked = 0, numShips = 0;
			for (Ship mine: gameMap.getMyPlayer().getShips().values()) {
				if (mine.getId() == noDockShip) continue;
				numShips++;
				if (mine.getDockingStatus() == DockingStatus.Undocked) numShipsUndocked++;
				double least = Double.MAX_VALUE;
				for (Ship enemy: gameMap.getAllShips()) {
					if (enemy.getOwner() == gameMap.getMyPlayerId()) continue;
					if (enemy.getDockingStatus() != DockingStatus.Undocked) continue;
					double dist = enemy.getDistanceTo(mine);
					if (dist < 100) totalEnemies++;
					if (dist < leastDist) leastDist = dist;
					if (dist < least) least = dist;
				}
				if (least > farthestDist) {
					farthestId = mine.getId();
					farthestDist = least;
				}
			}
			if (totalEnemies != 0) {
				if (leastDist > 20 && totalEnemies <= numShips) {
					allowClearDock = true;
				}
				else if (farthestDist > 119 && totalEnemies <= 3) {
					allowClearDock = true;
				}
				else if (totalEnemies <= numShips && farthestDist > 65) {
					canTryOutDock = farthestId;
				}
				if (leastDist > 50 && turnNum > 10 && totalEnemies <= numShips * (numShipsUndocked - 1) && farthestId != -1) {
					canTryOutDock = farthestId;
				}
			}
			else {
				allowClearDock = true;
			}
		}
		for (Ship s : gameMap.getAllShips()) {
			// Clump.add(s);
			if (s.getOwner() != gameMap.getMyPlayerId()) {
				int foundKey = -1;
				for (Ship s2: gameMap.getAllShips()) {
					if (s == s2) continue;
					if (s.getDistanceTo(s2) < SUPPORTER_SIGHT) {
						if (s2.getOwner() != gameMap.getMyPlayerId() && foundKey == -1) {
							Integer key = shipInd.get(s2.getId());
							if (key != null) {
								foundKey = key;
								shipInd.put(s.getId(), key);
								shipsCanFollow.put(key, shipsCanFollow.get(key) + ((rush4p || rush2p)? 2 : 3));
								break;
							}
						}
					}
				}
				if (foundKey == -1) {
					shipInd.put(s.getId(), s.getId());
					shipsCanFollow.put(s.getId(), ((rush4p || rush2p) ? 1 : 2));
				}
			}
		}
		int enemyPlanetsCount = 0, totalPlanetsCount = gameMap.getAllPlanets().values().size();
		averagePlanetPos = null;
		double totalX = 0, totalY = 0, count = 0;
		for (Planet p : gameMap.getAllPlanets().values()) {
			if (p.isOwned() && p.getOwner() == gameMap.getMyPlayerId()) {
				count++;
				totalX += p.getXPos();
				totalY += p.getYPos();
			}
			else if (p.isOwned()) {
				enemyPlanetsCount++;
			}
		}
		averagePlanetPos = new Position(totalX / count, totalY / count);
		double ratioToCenter = averagePlanetPos.getDistanceTo(new Position(gameMap.getWidth() / 2, gameMap.getHeight() / 2));
		ratioToCenter /= Math.sqrt(square(gameMap.getWidth() / 2) + square(gameMap.getHeight() / 2));
		if (!isTwoPlayer && ((
				(count <= 2 && enemyPlanetsCount / (double) totalPlanetsCount > 0.85) ||
				(count == 1 && enemyPlanetsCount / (double) totalPlanetsCount > 0.8) ||
				(count == 0 && enemyPlanetsCount / (double) totalPlanetsCount > 0.75) ||
				(count <= 5 && count > 2 && enemyPlanetsCount / (double) totalPlanetsCount > 0.9) ||
				(count <= 5 && enemyPlanetsCount / (double)totalPlanetsCount > 0.75 && ratioToCenter < 0.3))
				|| (myShipsCount * 6 < (gameMap.getAllShips().size() - myShipsCount) && turnNum > 75) ||
				   (myShipsCount * 5 < (gameMap.getAllShips().size() - myShipsCount) && turnNum > 75 && ratioToCenter < 0.3))) {
			totalAvoidMode = true;
		}
		noAggression = false;
		if (!isTwoPlayer && myShipsCount * 4 < (gameMap.getAllShips().size() - myShipsCount) && turnNum > 50) {
			noAggression = true;
		}
		rush2pAngle = -1;
		rush2pThrust = -1;
		rush2pTarget = null;
		rushRetreat = false;
		rushAdvance = false;
	}

	private void moveShips(Collection<Ship> shipsUnsorted) {
		Collection<Ship> ships = sortShips(shipsUnsorted);
		int i = -1;
		boolean totalDestructionMode = (ships.size() > 75 && ships.size() / (double) gameMap.getAllShips().size() > 0.5)
				|| (ships.size() > 25 && ships.size() / (double) gameMap.getAllShips().size() > 0.6);
		
		for (final Ship ship : ships) {
			flushLog();
			i++;
			moveShip(ship, i, totalDestructionMode);
			inMoveQueue.put(ship.getId(), false);
			if (System.currentTimeMillis() - startTime > 1900)
				break;
		}
	}
	
	private Collection<Ship> sortShips(Collection<Ship> ships) {
		// if I want to use this, I have to change some things.
		// currently, it will make ships very indecisive, because if only 2/3 ships can go to planet 4,
		// then the two closest to enemies will go for it, the other will go somewhere else.
		// the next turn though, that might change.
		ArrayList<Ship> sorted = new ArrayList<>();
		for (Ship s: ships) {
			
			// scored by (damage_taken).(id)
			// since we're sorting from smallest to largest, this will put
			// highest health ships first. In the case of a tie, lower ids are first.
			s.tmp = s.getId();//(255 - s.getHealth()) + s.getId() / 1000.0;
			if (turnNum == 0 && isTwoPlayer && rush2p && Math.abs(s.getXPos() - gameMap.getWidth() / 2.0) < 5) {
				if (s.getId() == 0)      s.tmp = 1;
				else if (s.getId() == 1) s.tmp = 2;
				else if (s.getId() == 2) s.tmp = 0;
				else if (s.getId() == 3) s.tmp = 1;
				else if (s.getId() == 4) s.tmp = 0;
				else if (s.getId() == 5) s.tmp = 2;
			}
			int i = 0;
			while (i < sorted.size() && sorted.get(i).tmp <= s.tmp)
				i++;
			sorted.add(i, s);
		}
		return sorted;
		//return ships;
	}

	private void moveShip(Ship ship, int i, boolean totalDestructionMode) {
		inMoveQueue.put(ship.getId(), true);
		int prevMoveListSize = moveList.size();

		if (System.currentTimeMillis() - startTime > 1900)
			return;
		sortedEntities = null;

		debugThisFrame = DEBUG && (DEBUG_FRAME == -1 || DEBUG_FRAME == turnNum)
				&& (DEBUG_SHIP == -1 || DEBUG_SHIP == ship.getId());
		logEntry("\n--- Debugging ship " + ship.getId() + " on frame " + turnNum + " ---\n");

		if (didShipsTurn.get(ship.getId()) != null && didShipsTurn.get(ship.getId()) == true)
			return;

		if (noDockShip == -1 && ship.getId() % 3 == 2)
			noDockShip = ship.getId();

		PastAction pastAction = getPastAction(ship);

		if (ship.getDockingStatus() != Ship.DockingStatus.Undocked) {
			Ship closestEnemy = null;
			if (totalAvoidMode) closestEnemy = getClosestEnemy(ship);
			if ((rush2p && closestEnemy != null && ship.getDistanceTo(closestEnemy) < 70) || (totalAvoidMode && closestEnemy != null && ship.getDistanceTo(closestEnemy) < 40)) {
				logEntry("Full send or total avoid mode enabled; undocking ship from planet " + ship.getDockedPlanet());
				moveList.add(new UndockMove(ship));
				didShipsTurn.put(ship.getId(), true);
			}
			return;
		}
		
		sortedEntities = gameMap.entitiesByDistance(ship);
		
		Surroundings surroundings = new Surroundings(this, ship, gameMap, sortedEntities);
		Planet[] planets = surroundings.planets;
		Ship[] enemyShipsClosest = surroundings.enemyShipsClosest;
		boolean destructorMode = surroundings.destructorMode;
		Ship target = surroundings.target;
		Planet myClosestPlanet = surroundings.myClosestPlanet;
		Planet goodPlanet = surroundings.goodPlanet;
		double goodPlanetScore = surroundings.goodPlanetScore;
		int supporters = surroundings.supporters, closeSupporters = surroundings.closeSupporters,
				challengers = surroundings.challengers, closeChallengers = surroundings.closeChallengers,
				protecting = surroundings.protecting,
				enemiesAttackingMe = surroundings.enemiesAttackingMe;
		Ship reallyCloseEnemy = surroundings.reallyCloseEnemy;
		
		if (rush4p && rush4pTarget != -1) {
			if (performRush4p(ship))
				return;
		}
		
		if (hideyShip == -1 && i == myShipsCount - 1
				&& myShipsCount / (double) gameMap.getAllShips().size() < 0.2
				&& myShipsCount >= NUM_SHIPS_BEFORE_HIDE) {
			hideyShip = ship.getId();
			logEntry("Set to hideyShip!");
		}
		
		if (totalAvoidMode) {
			logEntry("sending ship to hide");
			hide(ship, supporters, reallyCloseEnemy);
			return;
		}
		
		if (numPillaging / undockedShips < 0.7 && totalDestructionMode) {
			logEntry("performing total destruction mode");
			numPillaging = performTotalDestruction(numPillaging, ship, 0, 0);

		} else if (rush2p) {
			if (performRush2p(ship)) return;
		} else if (ship.getId() == hideyShip) {
			logEntry("sending ship to hide");
			hide(ship, supporters, reallyCloseEnemy);
			return;
		}
		if (moveList.size() != prevMoveListSize)
			return;
		else if (rush2p) {
			logEntry("cancelling rush2p");
			rush2p = false;
		}
		
		if (!isLeader(ship) || closeSupporters < challengers * 2) {
			logEntry("helping ships in distress");
			Ship closeDocked = getMyClosestDockedShip(ship);
			if (closeDocked != null && ship.getDistanceTo(closeDocked) < 5 && reallyCloseEnemy != null && closeDocked.getDistanceTo(reallyCloseEnemy) < DISTRESS_DISTANCE_DOCKED) {
				logEntry("ship " + closeDocked.getId() + " needs protecting");
				if (ship.getDistanceTo(reallyCloseEnemy) < 12 && battle(ship, myClosestPlanet, supporters, closeSupporters, challengers,
						closeChallengers, protecting, enemiesAttackingMe, reallyCloseEnemy)) {
					return;
				}
				Entity targetPos = createTarget(closeDocked, closeDocked.orientTowardsInRad(reallyCloseEnemy), 1);
				if (closeDocked.getDistanceTo(reallyCloseEnemy) > 5)
					targetPos = createTarget(closeDocked, closeDocked.orientTowardsInRad(reallyCloseEnemy), 3);
				final ThrustMove newThrustMove = nav(ship, targetPos, 7, NUM_CORRECTIONS,
						ANGLE_DELTA, 1.2, false);
				if (newThrustMove != null) {
					submitThrustMove(newThrustMove);
					DistressedShip distress = null;
					for (DistressedShip ds: distressList) {
						if (ds.caller.getId() == closeDocked.getId()) {
							distress = ds;
							break;
						}
					}
					if (distress != null) {
						distress.numProtecting++;
						if (distress.numProtecting >= distress.numEnemies) {
							distressList.remove(distress);
							logEntry("ship " + closeDocked.getId() + " removed from distressList");
						}
					}
					return;
				}
			}
			if (helpShipInDistress(ship))
				return;
		}
		if (pastAction instanceof LureAction) {
			logEntry("found pastAction of type LureAction");
			LureAction la = (LureAction) pastAction;
			if (reallyCloseEnemy != null && supporters < challengers * 3 + 1) {
				logEntry("luring enemy " + reallyCloseEnemy.getId());
				if (lureEnemy(ship, reallyCloseEnemy))
					return;
			} else {
				Ship enemy = gameMap.getShip(la.getEnemyPlayer(), la.getEnemyId());
				if (enemy == null || ship.getDistanceTo(enemy) > 20) {
					enemy = getClosestEnemy(ship);
				}
				if (enemy != null && ship.getDistanceTo(enemy) < 20) {
					logEntry("luring enemy " + enemy.getId());
					if (lureEnemy(ship, enemy))
						return;
				} else {
					logEntry("ended lure on " + la.getEnemyId());
					pastActions.remove(pastAction);
				}
			}
		}
		
		if (isLeader(ship)) {
			logEntry("I am a squad leader");
			SquadLeader leader = findClosestSquad(ship, ship);
			Position leaderShip = null;
			if (leader != null) {
				leaderShip = getShip(leader);
				if (leader.newestPosition != null)
					leaderShip = leader.newestPosition;
			}
			if (leaderShip != null && ship.getDistanceTo(leaderShip) < 2) {
				logEntry("psych! Now I'm not because " + leader.shipId + " is close");
				squads.remove(getLeader(ship));
				
			}
		}
		if (!squadsNearby(ship, SQUAD_NEARBY)) {
			if (closeSupporters >= 2) {
				squads.add(new SquadLeader(ship));
				logEntry("started squad");
			}
		} else if (!isLeader(ship)
				&& (!(goodPlanet != null && goodPlanetScore > LOCK_ON_GOOD_PLANET_SCORE && reallyCloseEnemy == null))) {
			SquadLeader leader = findClosestSquad(ship);
			Ship leaderShip = getShip(leader);
			if (leaderShip == null || leaderShip.getDockingStatus() != DockingStatus.Undocked) {
				squads.remove(leader);
				logEntry("deleting " + leader.shipId + " as a leader.");
				
			}
			else if (reallyCloseEnemy != null && !rush2p && ship.getDistanceTo(reallyCloseEnemy) < 10 && battle(ship, myClosestPlanet, supporters, closeSupporters, challengers, closeChallengers, protecting, enemiesAttackingMe, reallyCloseEnemy)) {
				return;
			}
			else if (ship.getDistanceTo(leader.newestPosition) > (3 + closeSupporters / 3.0) && leader != null && (leader.target == null || rush2p)) {
				logEntry("following " + leader.shipId);
				if ((leader.target == null || subtractFromFollowShip(leader.target))
						&& lockOn(ship, leader.newestPosition, challengers + supporters, false, 1.1, 7, null)) {
					leader.shipsFollowingThisTurn++;
					logEntry("locked on to leader's newest position");
					return;
				}
			} else if (leader != null && leader.target != null) {
				Position dontPass = leader.newestPosition;
				if (dontPass == null) dontPass = leaderShip;
				double myDist = ship.getDistanceTo(leader.target);
				double leaderDist = leaderShip.getDistanceTo(leader.target);
				if (leader.newestPosition != null) {
					leaderDist = leader.newestPosition.getDistanceTo(leader.target);
				}
				if (myDist < leaderDist - 1.5) {
					// closer than leader?? Back up dude!!!
					logEntry("Closer to target " + leader.target.getId() + " than leader " + leaderShip.getId() + ". backing up");
					if (lockOnMagnet(ship, dontPass, 1.5)) {
						leader.shipsFollowingThisTurn++;
						leader.shipsVeryCloseThisTurn++;
						return;
					}
				}
				int maxThrust;
				if (myDist > leaderDist + 7) {
					logEntry("pretty far away from target " + leader.target.getId() + ". free attack.");
					maxThrust = 7;
				}
				else {
					logEntry("sort of far away from target " + leader.target.getId() + ". adjusting thrust");
					maxThrust = (int)Math.min(7, Math.round(myDist - leaderDist));
					if (maxThrust <= 0) {
						submitThrustMove(new ThrustMove(ship, 1000, 0));
						leader.shipsFollowingThisTurn++;
						leader.shipsVeryCloseThisTurn++;
						return;
					}
				}
				logEntry("trying to lock on to leader's target");
				if (subtractFromFollowShip(leader.target)
						&& lockOn(ship, leader.target, challengers + supporters, false, 1.5, maxThrust, null)) {
					leader.shipsFollowingThisTurn++;
					leader.shipsVeryCloseThisTurn++;
					return;
				}
			}
		}
		
		logEntry("checking possible pillages");
		for (Ship ship1 : surroundings.possiblePillage) {
			if (!noAggression && tryPillage(ship, ship1, turnNum, numPillaging, undockedShips, goodPlanetScore, goodPlanet, supporters,
					closeSupporters, challengers))
				break;
		}
		if (moveList.size() != prevMoveListSize)
			return;

		if (reallyCloseEnemy != null && battle(ship, myClosestPlanet, supporters, closeSupporters, challengers,
				closeChallengers, protecting, enemiesAttackingMe, reallyCloseEnemy))
			return;

		if (goodPlanet != null && goodPlanetScore > LOCK_ON_GOOD_PLANET_SCORE && supporters >= challengers) {
			logEntry("finding planet to populate");
			findPlanetToPopulate(ship, planets, goodPlanet, goodPlanetScore, supporters, challengers);
			if (moveList.size() != prevMoveListSize) {
				return;
			}
		}
		
		if (destructorMode && !noAggression && target != null && target.getOwner() != gameMap.getMyPlayerId()) {
			logEntry("locking on to " + target.getId());
			if (subtractFromFollowShip(target) && lockOn(ship, target, challengers, supporters)) {
				return;
			}
		}

		if (!noAggression && noDockShip == ship.getId()) {
			logEntry("sneaky pillage");
			if (sneakyPillage(ship))
				return;
		}
		if (noDockShip == ship.getId() && reallyCloseEnemy != null) {
			logEntry("luring enemy because I'm noDockShip");
			if (lureEnemy(ship, reallyCloseEnemy)) return;
		}
		if (supporters >= 2 || challengers < 1 + supporters) {
			logEntry("attacking closest ship");
			if (!noAggression && isLeader(ship)) {
				SquadLeader sl = getLeader(ship);
				int tooManyProtectors = isTwoPlayer ? Math.max(1, closeSupporters - 2) : 1;
				if (sl != null) {
					tooManyProtectors = Math.max(2, sl.shipsFollowing - 1);
				}
				Ship closestDocked = getClosestDockedEnemyShip(ship, 7, tooManyProtectors);
				if (closestDocked != null && ship.getDistanceTo(closestDocked) < 10) {
					logEntry("attacking ship " + closestDocked.getId());
					if (lockOn(ship, closestDocked, challengers, supporters)) {
						return;
					}
				}
			}
			if (!noAggression && attackClosestShip(ship, enemyShipsClosest, goodPlanetScore, goodPlanet, supporters, closeSupporters, challengers))
				return;
		}

		if (isLeader(ship)) {
			squads.remove(getLeader(ship));
		}
		logEntry("finding planet to populate");
		findPlanetToPopulate(ship, planets, goodPlanet, goodPlanetScore, supporters, challengers);

		if (moveList.size() == prevMoveListSize) {
			if (hideyShip == -1 && gameMap.getMyPlayer().getShips().values().size() > NUM_SHIPS_BEFORE_HIDE) {
				logEntry("now hideyShip; hiding");
				hideyShip = ship.getId();
				hide(ship, supporters, reallyCloseEnemy);
				return;
			}
			if (squadsNearby(ship, 45)) {
				logEntry("joining squad");
				SquadLeader slC = findClosestSquad(ship);
				if (lockOn(ship, slC.newestPosition, false)) {
					slC.shipsFollowingThisTurn++;
					return;
				}
			}
			if (supporters >= 1) {
				for (Ship s: gameMap.getMyPlayer().getShips().values()) {
					MovingShip ms = getMovingShip(s);
					if (ms != null && ship.getDistanceTo(ms.posAfterThisTurn) < 9 && (averagePlanetPos == null || (ms.posAfterThisTurn.getDistanceTo(averagePlanetPos) > ship.getDistanceTo(averagePlanetPos)))) {
						logEntry("joining ship " + s.getId());
						ThrustMove tm = nav(ship, ms.posAfterThisTurn, (int)ship.getDistanceTo(ms.posAfterThisTurn), NUM_CORRECTIONS, ANGLE_DELTA, 1.1, false);
						if (tm == null) break;
						submitThrustMove(tm);
						return;
					}
				}
			}
			logEntry("no results: making any move");
			makeAnyMove(ship, prevMoveListSize, planets, enemyShipsClosest, supporters, challengers);

		}
	}

	public void logEntry(String entry) {
		if (DEBUG && debugThisFrame) {
			log.println(entry);
		}
	}

	public void flushLog() {
		if (DEBUG && debugThisFrame) {
			log.flush();
		}
	}

	private boolean battle(Ship ship, Planet myClosestPlanet, int supporters, int closeSupporters, int challengers,
			int closeChallengers, int protecting, int enemiesAttackingMe, Ship reallyCloseEnemy) {
		if (reallyCloseEnemy == null)
			return false;
		logEntry("battle called");
		Ship targetShip = reallyCloseEnemy;
		Ship closestDocked = getClosestDockedEnemyShip(ship, 0, 2);//4, closeSupporters > 3 ? 3 : 2);
		if (closestDocked != null && ((closeSupporters > challengers + 2 && closestDocked.getDistanceTo(ship) < 7 + WEAPON_RADIUS) || 
				(closeSupporters > 5 && protecting == 0 && closeSupporters > closeChallengers * 0.9 && supporters > challengers * 0.9 && closestDocked.getDistanceTo(ship) < 20))) {
			targetShip = closestDocked;
			logEntry("locking on to " + targetShip.getId());
			if (lockOn(ship, targetShip, false)) return true;
		}
		for (EntityNode curr = sortedEntities; curr != null; curr = curr.getNext()) {
			if (curr.getDistance() > Constants.DOCK_RADIUS) break;
			if (curr.getEntity() instanceof Planet && closeSupporters > challengers + 1 && (closeChallengers == 0 || closeSupporters > closeChallengers + 2) && supporters > challengers * 1.5 + 1) {
				Planet planet = (Planet)curr.getEntity();
				if (!planet.isFull() && getGoing(planet.getId()) < planet.getDockingSpots() - planet.getDockedShips().size()) {
					logEntry("docking onto " + planet.getId());
					moveList.add(new DockMove(ship, planet));
					addGoing(planet.getId());
					didShipsTurn.put(ship.getId(), true);
					willDock.put(ship.getId(), true);
					return true;
				}
			}
		}
		/*int maxThrust = 7;
		if (ship.getDistanceTo(targetShip) < WEAPON_RADIUS + 4) maxThrust = 4;*/
		if (supporters > 2 && supporters > challengers * 1.5 && closeSupporters > closeChallengers * 1.5 && subtractFromFollowShip(targetShip) && (lockOnMagnet(ship, targetShip, 3))) {
			logEntry("outnumbered enemies, so attacking");
			coordAttack.add(targetShip);
			return true;
		}
		else if ((closeSupporters >= 3 && supporters > challengers + 2 && closeSupporters > closeChallengers + 2) && //isCloseShipBeingAttacked(ship) && 
				((attackSingle(ship, targetShip) ||
				lockOnMagnet(ship, targetShip, WEAPON_RADIUS - 2)))) {
			logEntry("close ship being attacked, so also attacked.");
			coordAttack.add(targetShip);	
			return true;
		}
		else if ((closeSupporters >= 3 && supporters > challengers - 2 && closeSupporters > closeChallengers - 2) && isCloseShipBeingAttacked(ship)) {
			logEntry("close ship being attacked, so also attacked. (2)");
			ThrustMove tm = navDualDir(ship, 3, NUM_CORRECTIONS, ANGLE_DELTA, ship.orientTowardsInRad(targetShip),
					0, ship.getDistanceTo(targetShip), getEntitiesCloseTo(ship, 5), true);
			if (tm != null) submitThrustMove(tm);
			return true;
		}
		else if (supporters == 0 && challengers > 1 && protecting == 0 && lureEnemy(ship, targetShip)) {
			logEntry("was outnumbered, so lured");
			return true;
		}
		Position closestCoordAttack = getClosestCoordAttack(ship);
		if (closestCoordAttack != null && ship.getDistanceTo(closestCoordAttack) < 7 + WEAPON_RADIUS && (attackSingle(ship, targetShip) || lockOnMagnet(ship, closestCoordAttack, WEAPON_RADIUS - 1.5))) {
			logEntry("joined coord attack at " + closestCoordAttack);
			return true;
		}
		if (closeSupporters < 2 && closeChallengers > 2 && (protecting == 0 || closeChallengers > 4)) {
			return retreat(ship, targetShip, supporters);
		}
		else if (closeSupporters < closeChallengers * 0.6) {
			return retreat(ship, targetShip, supporters);
		}
		else if (targetShip.getDistanceTo(ship) < WEAPON_RADIUS && supporters <= challengers - 3 && protecting == 0) {
			return regroup(ship, supporters);
		}
		else if (isCloseShipBeingAttacked(ship) && supporters > challengers + 2 && closeSupporters > closeChallengers + 2 && (attackSingle(ship, targetShip) || lockOnMagnet(ship, targetShip, WEAPON_RADIUS - 1.5))) {
			logEntry("close ship being attacked, so also attacked (2)");
			coordAttack.add(targetShip);
			return true;
		}
		else if (protecting > 0 && supporters > challengers - 1 && closeSupporters > closeChallengers && (attackSingle(ship, targetShip) || lockOnMagnet(ship, targetShip, WEAPON_RADIUS - 2))) {
			logEntry("protecting, so attacking enemy");
			coordAttack.add(targetShip);
			return true;
		}
		/*else if (protecting >= 2 && closeSupporters < challengers - 3 && killBestValueShip(ship)) {
			logEntry("suiciding");
			return true;
		}*/
		//if (closeSupporters > closeChallengers && attackSingle(ship, reallyCloseEnemy)) return true;
		return regroup(ship, supporters);
	}

	private boolean attackSingle(Ship ship, Ship target) {
		ThrustMove tm = navSingleTarget(ship, target);
		if (tm == null) return false;
		logEntry("was attackSingle");
		submitThrustMove(tm);
		setLeadersTarget(ship, target);
		return true;
	}
	
	private boolean isCloseShipBeingAttacked(Ship ship) {
		for (Ship s : gameMap.getMyPlayer().getShips().values()) {
			Position p = s;
			MovingShip ms = getMovingShip(s);
			if (ms != null) p = ms.posAfterThisTurn;
			if (s.getDistanceTo(ship) < 7) {
				for (Ship s2 : gameMap.getAllShips()) {
					if (s2.getOwner() != gameMap.getMyPlayerId() && s2.getDistanceTo(p) < WEAPON_RADIUS) {
						return true;
					}
				}
			}
		}
		return false;
	}
	
	private boolean retreat(Ship ship, Ship reallyCloseEnemy, int supporters) {
		logEntry("retreat called");
		Position avgEnemy = getAverageEnemyPosition(ship, SUPPORTER_SIGHT);
		if (avgEnemy == null) avgEnemy = reallyCloseEnemy;
		Position p = createTarget(ship, avgEnemy.orientTowardsInRad(ship), 20);
		Ship closestDocked = getMyClosestDockedShip(ship);
		if (closestDocked != null && closestDocked.getDistanceTo(ship) < 10) p = closestDocked;
		return regroup(ship, p.getXPos() * 3, p.getYPos() * 3, 3, 10, true, SUPPORTER_SIGHT, supporters);
	}
	
	private boolean regroup(Ship ship, int supporters) {
		return regroup(ship, ship.getXPos(), ship.getYPos(), 1, 6, false, SUPPORTER_SIGHT, supporters);
	}
	private boolean regroup(Ship ship, double startTotalX, double startTotalY, double startCount, int dockedMultiplier, boolean avoidEnemy, int distance, int supporters) {
		double totalX = startTotalX, totalY = startTotalY, count = startCount;
		logEntry("regroup called with initial weight " + count + " and pos(" + (totalX/count) + ", " + (totalY/count));
		for (EntityNode curr = sortedEntities; curr != null; curr = curr.getNext()) {
			if (curr.getEntity() instanceof Ship) {
				Ship s = (Ship) curr.getEntity();
				double dist = ship.getDistanceTo(s);
				if (s.getOwner() == ship.getOwner() && dist < distance) {
					if (s.getDockingStatus() != DockingStatus.Undocked && dist < 7) {
						totalX += s.getXPos() * dockedMultiplier;
						totalY += s.getYPos() * dockedMultiplier;
						count += dockedMultiplier;
					}
					else {
						MovingShip ms = getMovingShip(s);
						totalX += s.getXPos();
						totalY += s.getYPos();
						count++;
						
						if (ms != null) {
							totalX += ms.posAfterThisTurn.getXPos();
							totalY += ms.posAfterThisTurn.getYPos();
							count += 1;
						}
					}
				} else if (s.getOwner() == ship.getOwner())
					break;
			}
		}
		if (count <= 1.01) {
			if (distance <= SUPPORTER_SIGHT) {
				return regroup(ship, 0, 0, 0, dockedMultiplier, avoidEnemy, distance * 2, supporters);
			}
			else {
				submitThrustMove(new ThrustMove(ship, 0, 0));
				return true;
			}
		}
		Position average = new Position(totalX / count, totalY / count);
		ThrustMove tm = null;
		Position closest = null;
		double closestDist = Double.MAX_VALUE;
		for (MovingShip ms: movingShips) {
			if (ms == null || ms.posAfterThisTurn == null) continue;
			double thisDist = ms.posAfterThisTurn.getDistanceTo(average);
			if (thisDist < closestDist) {
				closestDist = thisDist;
				closest = ms.posAfterThisTurn;
			}
		}
		if (closest != null && closestDist < 5) {
			if (closest.getDistanceTo(ship) < 8) {
				average = closest;
			}
		}
		
		// randomize average distance
		double offset = 1 + Math.sqrt(supporters) / 2.0;
		double offsetAngle = rand.nextDouble() * Math.PI * 2;
		average = new Position(average.getXPos() + offset * Math.cos(offsetAngle), average.getYPos() + offset * Math.sin(offsetAngle));
		
		double dist = average.getDistanceTo(ship);
		int thrust;
		if (dist > 15)
			thrust = 7;
		else if (dist > 10)
			thrust = 6;
		else if (dist > 7)
			thrust = 5;
		else if (dist > 5)
			thrust = 4;
		else if (dist > 3)
			thrust = 3;
		else thrust = 2;
		
		if (avoidEnemy) {
			tm = navAvoidEnemy(ship, 7, NUM_CORRECTIONS, ANGLE_DELTA, ship.orientTowardsInRad(average), 0, dist, -1);
		}
		else
			tm = nav(ship, average, thrust, NUM_CORRECTIONS, ANGLE_DELTA, 1.4, false);
		if (tm != null) {
			submitThrustMove(tm);
			return true;
		}
		return false;
	}

	private boolean lureEnemy(Ship ship, Ship closestEnemy) {
		logEntry("lure enemy called on ship " + closestEnemy);
		if (getPastAction(ship) == null) {
			LureAction la = new LureAction(ship.getId(), closestEnemy.getId(), closestEnemy.getOwner());
			pastActions.add(la);
		}
		Planet p = findMyClosestPlanet(ship);
		Position avgEnemy = getAverageEnemyPosition(ship, SUPPORTER_SIGHT);
		if (avgEnemy == null) avgEnemy = closestEnemy;
		double avoidAngleRad = ship.orientTowardsInRad(closestEnemy);
		double avoidAngleRad2 = -4 * Math.PI;
		double angleRad = avgEnemy.orientTowardsInRad(ship);
		if (p != null && p.getDistanceTo(ship) < 30) {
			avoidAngleRad2 = ship.orientTowardsInRad(p);
			angleRad = p.orientTowardsInRad(ship);
		}
		else if (p != null && averagePlanetPos != null
				&& isWithinRad(angleRad, ship.orientTowardsInRad(averagePlanetPos), Math.PI / 16)) {
			angleRad = averagePlanetPos.orientTowardsInRad(ship);
		}
		ThrustMove tm = null;
		Ship mine = getMyClosestDockedShip(ship);
		if (mine != null && (closestEnemy.getDistanceTo(mine) < 10 || ship.getDistanceTo(mine) < 10)) {
			tm = nav(ship, mine, 7, NUM_CORRECTIONS, ANGLE_DELTA, 1.2, false);
			if (tm != null) {
				submitThrustMove(tm);
				return true;
			}
		}
		Ship s = getClosestDockedEnemyShip(ship, 15, 2);
		if (s == null)
			s = getClosestDockedEnemyShip(ship, 5, 1);
		if (s != null) {
			double prevAngleRad = angleRad;
			angleRad = ship.orientTowardsInRad(s);
			if (isWithinRad(angleRad, avoidAngleRad, Math.PI / 5.0)
					|| isWithinRad(angleRad, avoidAngleRad2, Math.PI / 5.0)) {
				angleRad = prevAngleRad;
			}
		}
		if (tm == null) {
			tm = navAvoidEnemy(ship, 7, NUM_CORRECTIONS, ANGLE_DELTA, angleRad, 0, 10, -1);
		}
		if (tm != null) {
			submitThrustMove(tm);
			return true;
		}
		return false;
	}

	private boolean sneakyPillage(Ship ship) {
		Position target = getSneakyPillageTarget(ship);
		if (target == null)
			return false;
		ThrustMove tm = navAvoidEnemy(ship, 7, NUM_CORRECTIONS, ANGLE_DELTA, ship.orientTowardsInRad(target), 0,
				10, -1);
		if (tm != null) {
			submitThrustMove(tm);
			return true;
		}
		return false;
	}
	
	private Position getSneakyPillageTarget(Ship ship) {
		Position target = null;
		for (EntityNode curr = sortedEntities; curr != null; curr = curr.getNext()) {
			Entity e = curr.getEntity();
			if (e == null || !(e instanceof Ship) || e.getOwner() == gameMap.getMyPlayerId() || ((Ship)e).getDockingStatus() == DockingStatus.Undocked)
				continue;

			if (canReachEnemy(ship, (Ship)e)) {
				target = e;
				break;
			}
		}
		
		if (target == null) {
			target = getClosestDockedEnemyShip(ship, 15, 2);
		}
		if (target == null)
			target = getClosestDockedEnemyShip(ship, 5, 1);
		return target;
	}

	private boolean tryPillage(final Ship ship, Ship toPillage, int turnNum, double numPillaging, double undockedShips,
			double goodPlanetScore, Planet goodPlanet, int supporters, int closeSupporters, int challengers) {
		double dist = ship.getDistanceTo(toPillage);
		if (numPillaging / undockedShips < 0.4
				&& !isProtected(toPillage, 7, closeSupporters+1)
				&& (gameMap.getMyPlayer().getShips().values().size() > 5 || noDockShip == ship.getId())
				&& toPillage.getDockingStatus() != DockingStatus.Undocked
				&& ((turnNum > 35 && dist < PILLAGE_SIGHT) || (dist < PILLAGE_SIGHT / 6)
						|| (noDockShip == ship.getId() && dist < startPillageDistance))
				&& ((goodPlanet == null || goodPlanetScore < LOCK_ON_GOOD_PLANET_SCORE
						|| noDockShip == ship.getId() || (isLeader(ship) && !isProtected(toPillage, 7, Math.max(2, getLeader(ship).shipsFollowing-2)))))) {
			if (dist < WEAPON_RADIUS) {
				didShipsTurn.put(ship.getId(), true);
				return true;
			}
			ThrustMove tm = navAvoidEnemy(ship, 7, NUM_CORRECTIONS, ANGLE_DELTA,
					ship.orientTowardsInRad(toPillage), 0, dist, -1);
			if (tm != null) {
				submitThrustMove(tm);
				return true;
			}
		}
		return false;
	}

	private void findPlanetToPopulate(final Ship ship, Planet[] planets, Planet goodPlanet, double goodPlanetScore,
			int supporters, int challengers) {
		for (final Planet planet : planets) {
			if (planet == null)
				break;
			if (planet.isOwned() && planet.getOwner() != gameMap.getMyPlayerId()) {
				continue;
			}

			if (ship.canDock(planet) && !planet.isFull()) {
				
				if (!clearedForDock) {
					if (allowClearDock) {
						clearedForDock = true;
					}
					else if (canTryOutDock == ship.getId()) {
						canTryOutDock = -1;
					}
					else {
						Ship closestEnemy = getClosestEnemy(ship);
						if (closestEnemy != null && ship.getDistanceTo(closestEnemy) < 119) {
							submitThrustMove(new ThrustMove(ship, 0, 0));
							return;
						}
					}
				}
				if (supporters >= challengers || (supporters > 2 && challengers / supporters <= 2)) {
					moveList.add(new DockMove(ship, planet));
					addGoing(planet.getId());
					didShipsTurn.put(ship.getId(), true);
					willDock.put(ship.getId(), true);
				}
				break;
			} else if (planet.isOwned()) {

				if (!planet.isFull()
						&& getGoing(planet.getId()) < planet.getDockingSpots() - planet.getDockedShips().size()
						&& (supporters >= challengers || (supporters > 2 && challengers / supporters <= 2))) {
					final ThrustMove newThrustMove = navToDock(ship, planet);
					if (newThrustMove != null) {
						addGoing(planet.getId());
						submitThrustMove(newThrustMove);
						break;
					}
				}
				continue;
			}

			if (goodPlanet != null && goodPlanetScore > LOCK_ON_GOOD_PLANET_SCORE && planet != goodPlanet)
				continue;
			if (getGoing(planet.getId()) >= planet.getDockingSpots())
				continue;
			final ThrustMove newThrustMove = navToDock(ship, planet);
			if (newThrustMove != null) {
				addGoing(planet.getId());
				submitThrustMove(newThrustMove);
				break;
			}
		}
	}

	private boolean attackClosestShip(final Ship ship, Ship[] enemyShipsClosest, double goodPlanetScore,
			Planet goodPlanet, int supporters, int closeSupporters, int challengers) {
		for (final Ship ship1 : enemyShipsClosest) {
			if (ship1 == null)
				break;
			if ((ship.getDistanceTo(ship1) > 28 || (closeSupporters > 2 && challengers < 4) || (ship1.getDockingStatus() != DockingStatus.Undocked && challengers < closeSupporters + 1)) && ((ship1.getDistanceTo(ship) < AGGRESSION_SIGHT
					&& (goodPlanet == null || goodPlanetScore < LOCK_ON_GOOD_PLANET_SCORE))
					|| (ship1.getDistanceTo(ship) < startPillageDistance && noDockShip == ship.getId()))
					&& subtractFromFollowShip(ship1) && lockOnMagnet(ship, ship1)) {
				return true;
			}
		}
		return false;
	}

	private boolean helpShipInDistress(final Ship ship) {
		DistressedShip closestDistress = null;
		double closestDistance = -1;
		for (final DistressedShip ds : distressList) {
			if (ds.caller == ship)
				continue;// don't help yourself!
			double dist = ship.getDistanceTo(ds.caller);
			if (dist < DISTRESS_SIGHT && (closestDistress == null || dist < closestDistance)) {
				closestDistress = ds;
				closestDistance = dist;
			}
		}
		if (closestDistress != null) {
			if (ship.getDistanceTo(closestDistress.caller) > 14 && closestDistress.closestEnemyDist > 20)
				return false;
			Position target = createTarget(closestDistress.caller, closestDistress.caller.orientTowardsInRad(closestDistress.enemy), 1.2);
			if (closestDistress.caller.getDistanceTo(closestDistress.enemy) > 7)
				target = createTarget(closestDistress.caller, closestDistress.caller.orientTowardsInRad(closestDistress.enemy), 3);
			final ThrustMove newThrustMove = nav(ship, target, (int)Math.min(7, ship.getDistanceTo(target)), NUM_CORRECTIONS,
					ANGLE_DELTA, 1.2, false);
			if (newThrustMove != null) {
				submitThrustMove(newThrustMove);
				closestDistress.numProtecting++;
				if (closestDistress.numProtecting >= closestDistress.numEnemies)
					distressList.remove(closestDistress);
				return true;
			}
			return false;
		}
		return false;
	}

	private void makeAnyMove(final Ship ship, int prevMoveListSize, Planet[] planets, Ship[] enemyShipsClosest,
			int supporters, int challengers) {
		// Attack closest docked enemy ship (preferring closest planet)
		for (final Planet planet : planets) {
			if (planet == null)
				break;
			if (planet.isOwned() && planet.getOwner() != gameMap.getMyPlayerId()) {
				List<Integer> l = planet.getDockedShips();
				Ship target1 = gameMap.getShip(planet.getOwner(), l.get(l.size() - 1));
				if (lockOn(ship, target1, challengers, supporters)) {
					return;
				}
			}
		}
		// Attack any ship (preferring closest)
		for (final Ship ship1 : enemyShipsClosest) {
			if (ship1 == null)
				break;
			if (lockOn(ship, ship1, challengers, supporters)) {
				return;
			}
		}
	}

	private boolean killBestValueShip(Ship ship) {
		int score = Integer.MIN_VALUE;
		Ship best = null;
		for (EntityNode curr = sortedEntities; curr != null; curr = curr.getNext()) {
			if (curr.getDistance() <= Constants.MAX_SPEED && curr.getEntity() instanceof Ship && curr.getEntity().getOwner() != gameMap.getMyPlayerId()) {
				Ship s = (Ship) curr.getEntity();
				if (s.getWillBeDead()) continue;
				int thisScore = s.getHealth();
				if (s.getDockingStatus() != DockingStatus.Undocked)
					thisScore += 32;
				if (curr == sortedEntities) thisScore -= 64;
				if (thisScore > score)
					best = s;
			}
		}
		if (best == null)
			return false;

		if (lockOn(ship, best, true)) {
			best.setAsDead();
			return true;
		}
		return false;
	}

	private void hide(Ship ship, int supporters, Ship reallyCloseEnemy) {
		int targetX = 1, targetY = 1;
		if (ship.getXPos() >= gameMap.getWidth() / 2)
			targetX = gameMap.getWidth() - 2;
		if (ship.getYPos() >= gameMap.getHeight() / 2)
			targetY = gameMap.getHeight() - 2;
		Position target = new Position(targetX, targetY);
		if (ship.getDistanceTo(target) < 4) {
			if (supporters > 2) {
				final ThrustMove newThrustMove = nav(ship, createTarget(ship, target.orientTowardsInRad(ship), 10), 3, NUM_CORRECTIONS, ANGLE_DELTA, 0, false);
				submitThrustMove(newThrustMove);
				return;
			}
			moveList.add(new ThrustMove(ship, 0, 0));
			didShipsTurn.put(ship.getId(), true);
			return;
		}
		else if (ship.getDistanceTo(target) > 14 && reallyCloseEnemy != null && lureEnemy(ship, reallyCloseEnemy)) {
			return;
		}
		int speed = appropriateSpeed(ship, 0);
		//final ThrustMove newThrustMove = navigateAvoidEnemy(ship, speed, NUM_CORRECTIONS, ANGLE_DELTA, roundRad(ship.orientTowardsInRad(target)), 0, ship.getDistanceTo(target));
		final ThrustMove newThrustMove = nav(ship, target, speed, NUM_CORRECTIONS, ANGLE_DELTA, 2, true);
		if (newThrustMove != null) {
			submitThrustMove(newThrustMove);
			return;
		}
	}
	
	private boolean performRush4p(Ship ship) {
		logEntry("performing 4 player rush.");
		Player enemy = null;
		for (Player p: gameMap.getAllPlayers()) {
			if (p.getId() == rush4pTarget) enemy = p;
		}
		if (enemy == null || enemy.getShips().isEmpty()) {
			rush4p = false;
			rush4pTarget = -1;
			return false;
		}
		logEntry("rushing player " + rush4pTarget);
		double minDist = Double.MAX_VALUE;
		Ship target = null;
		for (Ship e: enemy.getShips().values()) {
			double dist = ship.getDistanceTo(e);
			if (target == null ||
					(target.getDockingStatus() == DockingStatus.Undocked && e.getDockingStatus() != DockingStatus.Undocked && canSubtractFromFollowShip(e)) ||
					(target.getDockingStatus() == DockingStatus.Undocked && e.getDockingStatus() == DockingStatus.Undocked && ((dist < minDist && canSubtractFromFollowShip(e) == canSubtractFromFollowShip(target)) || (canSubtractFromFollowShip(e) && !canSubtractFromFollowShip(target)))) ||
					(e.getDockingStatus() != DockingStatus.Undocked && dist < minDist && canSubtractFromFollowShip(e))) {
				target = e;
				minDist = dist;
			}
		}
		if (target != null && ship.getDistanceTo(target) < 119) {
			if ((rushRetreat && target.getDockingStatus() == DockingStatus.Undocked) || (!rushAdvance && target.getDockingStatus() == DockingStatus.Undocked && ship.getDistanceTo(target) < 7 + WEAPON_RADIUS && enemy.getShips().values().size() >= gameMap.getMyPlayer().getShips().values().size())) {
				Position target2 = createTarget((Position)ship, target.orientTowardsInRad(ship), 8);
				int thrust = (int)(Math.min(7, Math.max(1, -ship.getDistanceTo(target) + 5 + WEAPON_RADIUS)));
				if (lockOnMagnet(ship, target2, 0.01, thrust, null)) {
					rushRetreat = true;
					return true;
				}
			}
			logEntry("found target: ship "+ target.getId());
			int maxThrust = 7;
			if (ship.getDistanceTo(target) < WEAPON_RADIUS + 7) maxThrust = 4;
			if (subtractFromFollowShip(target) && (lockOnAvoidEnemy(ship, target, 0, false, 1.01, maxThrust, rush4pTarget))) return true;
			if (enemy.getShips().size() > gameMap.getMyPlayer().getShips().values().size() - 2) {
				rushAdvance = true;
				return lockOnMagnet(ship, target);
			}
		}
		logEntry("rushing failed.");
		return false;
	}
	
	private boolean performRush2p(Ship ship) {
		logEntry("performing 2 player rush");
		if (turnNum == 0 && Math.abs(ship.getXPos() - gameMap.getWidth() / 2.0) < 5) {
			Position target = null;
			if (ship.getId() == 1) { // upper top
				target = new Position(ship.getXPos(), ship.getYPos() + 7);
			}
			else if (ship.getId() == 0) { // upper middle
				target = new Position(ship.getXPos() - 2, ship.getYPos() + 4);
			}
			else if (ship.getId() == 2) { // upper bottom
				target = new Position(ship.getXPos() + 2, ship.getYPos() + 1);
			}
			else if (ship.getId() == 4) { // lower top
				target = new Position(ship.getXPos() - 2, ship.getYPos() - 1);
			}
			else if (ship.getId() == 3) { // lower middle
				target = new Position(ship.getXPos() + 2, ship.getYPos() - 4);
			}
			else if (ship.getId() == 5) { // lower bottom
				target = new Position(ship.getXPos(), ship.getYPos() - 7);
			}
			ThrustMove tm = nav(ship, target, Math.min(7, (int)Math.ceil(ship.getDistanceTo(target))), NUM_CORRECTIONS, ANGLE_DELTA, 0, false, 0);
			if (tm != null) {
				submitThrustMove(tm);
				return true;
			}
		}
		if (turnNum > 25 && myShipsCount >= gameMap.getAllShips().size() - myShipsCount) {
			for (Ship s: gameMap.getAllShips()) {
				if (s.getOwner() == gameMap.getMyPlayerId()) continue;
				if (attackSingle(ship, s)) return true;
			}
		}
		if (rush2pAngle != -1 && rush2pThrust != -1 && canSubtractFromFollowShip(rush2pTarget)) {
			submitThrustMove(new ThrustMove(ship, rush2pAngle, rush2pThrust));
			return true;
		}
		
		boolean wait = false, allClose = true;
		Position closest = null, farthest = null;
		ArrayList<Ship> myShips = new ArrayList<>();
		for (Ship s : gameMap.getMyPlayer().getShips().values()) {
			if (getMovingShip(s) == null)
				myShips.add(s);
			if (s.getDockingStatus() != DockingStatus.Undocked) {
				wait = true;
			}
			MovingShip ms = getMovingShip(s);
			Position now = (ms == null ? s : ms.posAfterThisTurn);
			if (ship.getDistanceTo(s) > 3) allClose = false;
			if (closest == null || ship.getDistanceTo(closest) > ship.getDistanceTo(now))
				closest = now;
			if (farthest == null || ship.getDistanceTo(farthest) < ship.getDistanceTo(now))
				farthest = now;
		}
		if (!allClose && closest != null && gameMap.getAllShips().size() - myShipsCount >= myShipsCount) {
			if (ship.getDistanceTo(closest) > 3) {
				logEntry("regrouping ship");
				int thrust = Math.min(7, (int)ship.getDistanceTo(closest));
				Ship closestEnemy = getClosestEnemy(closest);
				Entity target = createTarget(ship, closest.orientTowardsInRad(closestEnemy) + Math.PI / 2.0, 2);
				ThrustMove tm = nav(ship, target, thrust, NUM_CORRECTIONS,
						ANGLE_DELTA, 1.2, false);
				if (tm != null) {
					submitThrustMove(tm);
					return true;
				}
			}
			else if (ship.getDistanceTo(farthest) > 6) {
				logEntry("moving towards farthest");
				int thrust = Math.min(7, (int)ship.getDistanceTo(farthest));
				if (farthest instanceof Ship) thrust = Math.min(7, (int)(ship.getDistanceTo(farthest)/2.0));
				ThrustMove tm = nav(ship, farthest, thrust, NUM_CORRECTIONS,
						ANGLE_DELTA, 1.2, true);
				if (tm != null) {
					submitThrustMove(tm);
					return true;
				}
			}
		}
		
		if (!wait) {
			Ship target2 = null;
			Ship closestEnemy = null;
			double minDist = Double.MAX_VALUE;
			for (Ship s : gameMap.getAllShips()) {
				double d = ship.getDistanceTo(s);
				if (s.getOwner() != gameMap.getMyPlayerId() &&
						(target2 == null ||
						(target2.getDockingStatus() == DockingStatus.Undocked && s.getDockingStatus() != DockingStatus.Undocked && canSubtractFromFollowShip(s)) ||
						(target2.getDockingStatus() == DockingStatus.Undocked && s.getDockingStatus() == DockingStatus.Undocked && ((d < minDist && canSubtractFromFollowShip(s) == canSubtractFromFollowShip(target2)) || (canSubtractFromFollowShip(s) && !canSubtractFromFollowShip(target2)))) ||
						(s.getDockingStatus() != DockingStatus.Undocked && d < minDist && canSubtractFromFollowShip(s)))) {
					target2 = s;
					minDist = d;
				}
				if (s.getOwner() != gameMap.getMyPlayerId() && (closestEnemy == null || closestEnemy.getDistanceTo(ship) > d)) {
					closestEnemy = s;
				}
			}
			if (target2 != null) {
				if (closestEnemy != null && ship.getDistanceTo(closestEnemy) > AUTO_RUSH2P - turnNum * 2 && turnNum > 3) {
					// call off full send.
					rush2p = false;
					return false;
				}
				else if ((rushRetreat && target2.getDockingStatus() == DockingStatus.Undocked) || (!rushAdvance && closestEnemy != null && target2.getDockingStatus() == DockingStatus.Undocked && ship.getDistanceTo(closestEnemy) < 7 + WEAPON_RADIUS  && gameMap.getAllShips().size() - myShipsCount >=  myShipsCount)) {
					Position target = createTarget((Position)ship, closestEnemy.orientTowardsInRad(ship), 8);
					int thrust = (int)(Math.min(7, -ship.getDistanceTo(closestEnemy) + 5 + WEAPON_RADIUS));
					if (lockOnMagnet(ship, target, 0.01, thrust, null)) {
						rushRetreat = true;
						return true;
					}
				}
				if ((target2.getDockingStatus() != DockingStatus.Undocked || ship.getHealth() > target2.getHealth()) || subtractFromFollowShip(target2)) {
					/*int maxThrust = 7;
					if (ship.getDistanceTo(target2) < WEAPON_RADIUS + 7) maxThrust = 4;
					if (canSubtractFromFollowShip(target2, myShips.size() - 1)) {
						if (lockOnGroup(ship, target2, 0, 2, maxThrust, myShips)) {
							ThrustMove tm = (ThrustMove)moveList.get(moveList.size() - 1);
							rush2pAngle = tm.getAngle();
							rush2pThrust = tm.getThrust();
							rush2pTarget = target2;
							return true;
						}
					}
					else*/
					rushAdvance = true;
					return (lockOnMagnet(ship, target2));
				}
			}
		} else {
			logEntry("not moving this turn");
			didShipsTurn.put(ship.getId(), true);
			return true;
		}
		return false;
	}

	private double performTotalDestruction(double numPillaging, final Ship ship, int supporters, int challengers) {
		for (EntityNode curr = sortedEntities; curr != null; curr = curr.getNext()) {
			Entity e = curr.getEntity();
			if ((e instanceof Ship) && e.getOwner() != gameMap.getMyPlayerId() && ((Ship)e).getDockingStatus() != DockingStatus.Undocked) {
				if (lockOn(ship, e, true)) {
					numPillaging++;
					break;
				}
			} else if ((e instanceof Planet)) {
				Planet p = (Planet) e;
				if ((!p.isOwned() || p.getOwner() == gameMap.getMyPlayerId()) && ship.canDock(p) && !p.isFull() && supporters > challengers + 2) {
					moveList.add(new DockMove(ship, p));
					addGoing(p.getId());
					didShipsTurn.put(ship.getId(), true);
					willDock.put(ship.getId(), true);
					break;
				}
			}
		}
		return numPillaging;
	}
	
	private void submitThrustMove(ThrustMove tm) {
		moveList.add(tm);
		addNewPosition(tm);
		movedShip.put(tm.getShip().getId(), true);
		didShipsTurn.put(tm.getShip().getId(), true);
	}

	private boolean isWithinRad(double angle1, double angle2, double d) {
		return Math.min(angle1 - angle2, Math.min(angle1 - angle2 + Math.PI * 2, angle1 - angle2 - Math.PI * 2)) <= d;
	}

	private double roundRad(double rad) {
		// Rounding to nearest DEGREE, so Math.PI / 180.
		final double step = Math.PI / 180.0;
		final double offset = rad % step;
		if (offset >= step / 2) return rad + step - offset;
		else return rad - offset;
	}
	
	private Position getAverageEnemyPosition(Ship ship, double radius) {
		double totalX = 0, totalY = 0, count = 0;
		for (EntityNode curr = sortedEntities; curr != null; curr = curr.getNext()) {
			if (curr.getDistance() > radius) break;
			Entity e = curr.getEntity();
			if (e instanceof Ship && e.getOwner() != gameMap.getMyPlayerId() && ((Ship)e).getDockingStatus() == DockingStatus.Undocked) {
				totalX += e.getXPos();
				totalY += e.getYPos();
				count++;
			}
		}
		if (count == 0) return null;
		return new Position(totalX/count, totalY/count);
	}

	private Ship getClosestDockedEnemyShip(Ship ship, double protectedDist, int numProtectors) {
		Ship closest = null;
		double minDist = Double.MAX_VALUE;
		for (Ship s : gameMap.getAllShips()) {
			if (s.getOwner() == gameMap.getMyPlayerId())
				continue;
			if (s.getDockingStatus() == DockingStatus.Undocked)
				continue;
			if (isProtected(ship, protectedDist, numProtectors))
				continue;
			double dist = ship.getDistanceTo(s);
			if (dist < minDist) {
				minDist = dist;
				closest = s;
			}
		}
		return closest;
	}
	
	private Position getClosestCoordAttack(Ship s) {
		Position closest = null;
		double dist = Double.MAX_VALUE;
		for (Position s2: coordAttack) {
			double newDist = s.getDistanceTo(s2);
			if (newDist < dist) {
				dist = newDist;
				closest = s2;
			}
		}
		return closest;
	}
	
	private Ship getMyClosestDockedShip(Position e) {
		Ship closest = null;
		double dist = Double.MAX_VALUE;
		for (Ship s : gameMap.getMyPlayer().getShips().values()) {
			if (s.getDockingStatus() != DockingStatus.Undocked) {
				double d = e.getDistanceTo(s);
				if (d < dist) {
					dist = d;
					closest = s;
				}
			}
		}
		return closest;
	}
	
	private MovingShip getClosestMovingShip(Position p) {
		MovingShip closest = null;
		double dist = Double.MAX_VALUE;
		for (MovingShip ms: movingShips) {
			Position p2 = ms;
			if (ms.posAfterThisTurn != null) p2 = ms.posAfterThisTurn;
			double d = p.getDistanceTo(p2);
			if (d < dist) {
				dist = d;
				closest = ms;
			}
		}
		return closest;
	}
	
	private Position getClosestShipPoint(Position p, Ship exclude) {
		Position closest = null;
		double dist = Double.MAX_VALUE;
		for (Ship s: gameMap.getMyPlayer().getShips().values()) {
			if (s == exclude) continue;
			double d = p.getDistanceTo(s);
			if (d < dist) {
				dist = d;
				closest = s;
			}
		}
		for (MovingShip ms: movingShips) {
			if (ms.posAfterThisTurn == null) continue;
			double d = ms.posAfterThisTurn.getDistanceTo(p);
			if (d < dist) {
				dist = d;
				closest = ms;
			}
		}
		return closest;
	}

	private boolean isProtected(Ship ship, double dist, int numProtectors) {
		// define protected as numProtectors ships within dist units.
		if (dist == 0 || numProtectors == 0) return false;
		int num = 0;
		ArrayList<Entity> enemyPlanets = new ArrayList<>();
		for (Planet p: gameMap.getAllPlanets().values()) {
			if (p.isOwned() && p.getOwner() == ship.getOwner())
				enemyPlanets.add(p);
		}
		for (Ship s : gameMap.getAllShips()) {
			if (s.getOwner() != ship.getOwner() ||
				s == ship || s.getDockingStatus() != DockingStatus.Undocked ||
				s.getDistanceTo(ship) > dist)
				continue;
			Entity obj = getObjectBetween(s, ship, 1, enemyPlanets, 7, false);
			if (obj == null)
				num++;
			if (num >= numProtectors)
				return true;
		}
		return false;
	}

	private boolean wouldDieNextTurn(Ship ship) {
		double damage = 0;
		for (EntityNode curr = sortedEntities; curr != null; curr = curr.getNext()) {
			Entity e = curr.getEntity();
			if (e.getDistanceTo(ship) > WEAPON_RADIUS)
				break;
			if (!(e instanceof Ship))
				continue;
			if (e.getOwner() == ship.getOwner())
				continue;
			Ship enemy = (Ship) e;
			if (enemy.getDockingStatus() != DockingStatus.Undocked)
				continue;

			// determine how many ships this ship is attacking
			int numShips = 0;
			for (Ship s : gameMap.getAllShips()) {
				if (s.getOwner() == enemy.getOwner())
					continue;
				if (s.getDistanceTo(enemy) > WEAPON_RADIUS)
					continue;
				numShips++;
			}
			damage += 128.0 / numShips;
		}
		return (damage + 0.5 > ship.getHealth());

	}

	private Ship createTarget(Ship p, double angleRad, int dist) {
		return new Ship(-1, -1, p.getXPos() + Math.cos(angleRad) * dist, p.getYPos() + Math.sin(angleRad) * dist,
				0, DockingStatus.Undocked, 0, 0, 0);
	}
	
	private Position createTarget(Position p, double angleRad, double dist) {
		return new Position(p.getXPos() + Math.cos(angleRad) * dist, p.getYPos() + Math.sin(angleRad) * dist);
	}
	
	private Planet findMyClosestPlanet(Ship ship) {
		Planet closest = null;
		double minDist = Double.MAX_VALUE;
		for (Planet p : gameMap.getAllPlanets().values()) {
			if (!p.isOwned() || p.getOwner() != gameMap.getMyPlayerId())
				continue;
			double d = p.getDistanceTo(ship);
			if (d < minDist) {
				closest = p;
				minDist = d;
			}
		}
		return closest;
	}
	
	private Ship getClosestEnemy(Position closest2) {
		Ship closest = null;
		double minDist = Double.MAX_VALUE;
		for (Ship s2 : gameMap.getAllShips()) {
			if ((closest2 instanceof Ship && s2.getOwner() != ((Ship)closest2).getOwner()) || s2.getOwner() != gameMap.getMyPlayerId()) {
				double d = closest2.getDistanceTo(s2);
				if (d < minDist) {
					minDist = d;
					closest = s2;
				}
			}
		}
		return closest;
	}

	private PastAction getPastAction(Ship ship) {
		for (PastAction pa : pastActions) {
			if (pa.getShipId() == ship.getId())
				return pa;
		}
		return null;
	}

	private boolean shouldRush2p() {
		for (Ship s : gameMap.getAllShips()) {
			if (s.getOwner() != gameMap.getMyPlayerId()) {
				return shouldRush2p(s);
			}
		}
		return false;
	}

	private boolean shouldRush2p(Position predictedPos) {
		boolean rush2p = false;
		if (isTwoPlayer) {
			Ship mine = null;
			for (Ship s : gameMap.getAllShips()) {
				if (s.getOwner() == gameMap.getMyPlayerId()) {
					mine = s;
					break;
				}
			}
			if (mine.getDistanceTo(predictedPos) < AUTO_RUSH2P) {
				rush2p = true;
				double dist = mine.getDistanceTo(predictedPos);
				for (Planet p : gameMap.getAllPlanets().values()) {
					if (dist / mine.getDistanceTo(p) > 4) {
						rush2p = false;
						break;
					}
				}
			}
		}
		return rush2p;
	}

	private boolean shouldRush2pMidgame(boolean enemyHasPlanets) {
		if (isTwoPlayer && !enemyHasPlanets) {
			List<Player> players = gameMap.getAllPlayers();
			if (players.size() == 2) {
				Collection<Ship> ships1 = players.get(0).getShips().values();
				Collection<Ship> ships2 = players.get(1).getShips().values();
				for (Ship ship1 : ships1) {
					for (Ship ship2 : ships2) {
						if (ship1.getDistanceTo(ship2) > AUTO_RUSH2P * 2.0 / 3.0)
							return rush2p;
					}
				}
				return true;
			}
		}
		return rush2p;
	}

	private void setLeadersTarget(Ship s, Entity target) {
		SquadLeader sl = getLeader(s);
		if (sl != null) {
			sl.target = target;
		}
	}

	private boolean isLeader(Ship ship) {
		for (SquadLeader s : squads) {
			if (s.shipId == ship.getId())
				return true;
		}
		return false;
	}

	private SquadLeader getLeader(Ship ship) {
		for (SquadLeader sl : squads) {
			if (sl.shipId == ship.getId())
				return sl;
		}
		return null;
	}

	private ArrayList<Ship> getLeaders() {
		ArrayList<Ship> ships = new ArrayList<>();
		for (SquadLeader sl : squads) {
			Ship s = getShip(sl);
			if (s != null)
				ships.add(s);
		}
		return ships;
	}

	private Ship getShip(SquadLeader sl) {
		return gameMap.getShip(sl.shipOwner, sl.shipId);
	}
	
	private SquadLeader findClosestSquad(Ship ship) {
		return findClosestSquad(ship, null);
	}
	
	private SquadLeader findClosestSquad(Ship ship, Ship dontReturnThisOne) {
		double distance = Double.MAX_VALUE;
		SquadLeader closest = null;
		for (SquadLeader leader : squads) {
			if (dontReturnThisOne != null && leader.shipId == dontReturnThisOne.getId()) continue;
			double d = ship.getDistanceTo(leader.newestPosition);
			if (d < distance) {
				distance = d;
				closest = leader;
			}
		}
		return closest;
	}

	private boolean squadsNearby(Ship ship, double distance) {
		for (SquadLeader leader : squads) {
			Ship s = getShip(leader);
			if (s != null) {
				if (ship.getDistanceTo(s) <= distance)
					return true;
			}
		}
		return false;
	}

	private boolean isPlanetBetter(Planet p, Double distanceFromShip, Planet goodPlanet, double goodPlanetScore) {
		boolean planetBetter = false;
		if (distanceFromShip < PLANET_TOO_FAR && !p.isOwned()) {
			if (goodPlanet == null) {
				planetBetter = true;
			} else if (scorePlanet(p, distanceFromShip) > goodPlanetScore) {
				planetBetter = true;
			}
		}
		return planetBetter;
	}

	private double scorePlanet(Planet p, double distanceToShip) {
		boolean isEarlyGame = gameMap.getMyPlayer().getShips().size() <= 3 && turnNum <= 20;
		double distToCenter = p.getDistanceTo(new Position(gameMap.getWidth() / 2, gameMap.getHeight() / 2));
		double ratioToCenter = distToCenter
				/ Math.sqrt(square(gameMap.getWidth() / 2) + square(gameMap.getHeight() / 2));
		int emptyPlanetsNearby = 0;
		int enemyPlanetsNearby = 0;
		if (isTwoPlayer) {
			ratioToCenter = 0.5 + 0.5 * (1 - ratioToCenter);
		}
		if (!isTwoPlayer && distToCenter < 15) emptyPlanetsNearby -= 2;
		
		for (Planet p2 : gameMap.getAllPlanets().values()) {
			if (p2 == p)
				continue;
			if (p2.isOwned()) {
				if (p2.getOwner() != gameMap.getMyPlayerId() && p.getDistanceTo(p2) < 40)
					enemyPlanetsNearby++;
				continue;
			}
			if (p.getDistanceTo(p2) < 50)
				emptyPlanetsNearby++;
		}
		if (emptyPlanetsNearby < 0) emptyPlanetsNearby = 0;
		
		if (!isTwoPlayer && enemyPlanetsNearby == 0 && Math.min(Math.min(p.getXPos(), gameMap.getWidth() - p.getXPos()), Math.min(p.getYPos(), gameMap.getHeight() - p.getYPos())) < gameMap.getWidth() / 4) {
			ratioToCenter *= 1.3;
		}
		if (!isTwoPlayer && enemyPlanetsNearby == 0 && Math.min(p.getXPos(), gameMap.getWidth() - p.getXPos()) < gameMap.getWidth() / 4) {
			ratioToCenter *= 1.4;
			if (isEarlyGame) {
				ratioToCenter *= 1.5;
			}
		}
		if (!isTwoPlayer && isEarlyGame) {
			ratioToCenter *= 4;
			if (emptyPlanetsNearby <= 1) ratioToCenter *= 0.5;
		}
		double emptyPlanetsMult = 1;
		if (isEarlyGame) {
			emptyPlanetsMult = 1.2;
		}
		
		double penalty = 0;
		if (isEarlyGame && emptyPlanetsNearby == 0) {
			penalty = isTwoPlayer ? 30 : 20;
		}
		// Ideally, 350 for distance, 50 for board position, 160 for size, 50 for empty planets nearby.
		// This is 610 total. So that we can settle for worse ones, GOOD_PLANET_SCORE is 510.
		double score = (400 - 3.3 * (distanceToShip)) + ratioToCenter * 50 + 20 * p.getRadius() + emptyPlanetsNearby * emptyPlanetsMult * 25 - enemyPlanetsNearby * 15 - penalty;
		return score;
	}

	private boolean subtractFromFollowShip(Entity shipToFollow) {
		if (!(shipToFollow instanceof Ship))
			return true;
		Integer key = shipInd.get(shipToFollow.getId());
		if (key == null) return true;
		Integer numCanFollow = shipsCanFollow.get(key);
		if (numCanFollow == null)
			return true;
		if (numCanFollow <= 0)
			return false;
		shipsCanFollow.put(key, numCanFollow - 1);
		return true;
	}
	
	private boolean canSubtractFromFollowShip(Entity shipToFollow) {
		return canSubtractFromFollowShip(shipToFollow, 1);
	}
	private boolean canSubtractFromFollowShip(Entity shipToFollow, int num) {
		if (!(shipToFollow instanceof Ship))
			return true;
		Integer key = shipInd.get(shipToFollow.getId());
		if (key == null) return true;
		Integer numCanFollow = shipsCanFollow.get(key);
		if (numCanFollow == null)
			return true;
		if (numCanFollow < num)
			return false;
		return true;
	}

	private double countMyUndocked(Collection<Ship> ships) {
		double undockedShips = 0;
		for (final Ship ship : ships) {
			if (ship.getDockingStatus() == DockingStatus.Undocked)
				undockedShips++;
		}
		return undockedShips;
	}

	private boolean checkDistressCalls(Collection<Ship> myShips, boolean enemyOwnsPlanet) {
		ArrayList<Ship> causedDistress = new ArrayList<>();
		for (Ship ship : myShips) {
			if (ship.getDockingStatus() == DockingStatus.Undocked)
				continue;
			// Send out distress call if there's a ship attacking me
			int count = 0;
			causedDistress.clear();
			double closestDist = Double.MAX_VALUE;
			Ship closestEnemy = null;
			for (Ship ship2 : gameMap.getAllShips()) {
				if (ship2.getOwner() == gameMap.getMyPlayerId() || ship2.getDockingStatus() != DockingStatus.Undocked)
					continue;
				double dist = ship.getDistanceTo(ship2);
				boolean isWithinDist = dist < DISTRESS_DISTANCE_DOCKED;
				
				if (ship2.getOwner() != gameMap.getMyPlayerId() && isWithinDist) {
					count++;
					causedDistress.add(ship2);
					if (dist < closestDist) {
						closestDist = dist;
						closestEnemy = ship2;
					}
				}
			}
			if (count > 0 && closestEnemy != null) {
				boolean isProtected = isProtected(ship, 3.5, count > 2 ? 2 : 1) && isProtected(ship, 20, count);
				if (!isProtected) {
					distressList.add(new DistressedShip(ship, closestEnemy, count > 5 ? 5 : (count > 2 ? 2 : 1), closestDist));
				}
				if (gameMap.getAllShips().size() <= 6 && isTwoPlayer && myShips.size() == 3 && !enemyOwnsPlanet) {
					rush2p = true;
				}
			}
		}
		return rush2p;
	}
	public static class DistressedShip {
		Ship caller;
		Ship enemy;
		int numEnemies;
		int numProtecting = 0;
		double closestEnemyDist;
		public DistressedShip(Ship me, Ship them, int count, double enemyDist) {
			caller = me;
			enemy = them;
			numEnemies = count;
			closestEnemyDist = enemyDist;
		}
	}
	
	public static class Clump {
		public final int owner;
		public static final double CLUMP_DIST = 6;// = weapon radius
		private static final double CHECK_CLUMP_DIST = 30;// max distance from clump's average pos to check if it is in a clump
		public static ArrayList<Clump> clumps = new ArrayList<>();
		private Position center = null;
		private ArrayList<Ship> ships = new ArrayList<>();
		
		private Clump(Ship initial) {
			owner = initial.getOwner();
			addShip(initial);
		}
		
		public static void add(Ship s) {
			// already in a clump
			if (get(s) != null) return;
			
			// close to a clump
			for (Clump c: clumps) {
				if (c.owner == s.getOwner() && c.center.getDistanceTo(s) < CHECK_CLUMP_DIST) {
					for (Ship s2: c.ships) {
						if (s.getDistanceTo(s2) < CLUMP_DIST) {
							c.addShip(s);
							return;
						}
					}
				}
			}
			Clump c = new Clump(s);
			clumps.add(c);
		}
		
		public static Clump get(Ship s) {
			for (Clump c: clumps) {
				if (c.owner == s.getOwner() && c.ships.contains(s)) {
					return c;
				}
			}
			return null;
		}
		
		private void addShip(Ship s) {
			ships.add(s);
			if (ships.size() == 1) {
				center = s;
			}
			else {
				center = new Position((center.getXPos() * (ships.size() - 1) + s.getXPos()) / ships.size(), (center.getYPos() * (ships.size() - 1) + s.getYPos()) / ships.size());
			}
		}
		
		public int getSize() {
			return ships.size();
		}
		
		public ArrayList<Ship> getShips() {
			ArrayList<Ship> tmp = new ArrayList<Ship>();
			for (Ship s: ships) {
				tmp.add(s);
			}
			return tmp;
		}
		
		public boolean isAnyWithin(Position p, double dist) {
			for (Ship s: ships) {
				if (s.getDistanceTo(p) < dist) return true;
			}
			return false;
		}
	}

	private int countEnemyPlanets() {
		int planets = 0;
		for (Planet p : gameMap.getAllPlanets().values()) {
			if (p.isOwned() && p.getOwner() != gameMap.getMyPlayerId()) {
				planets++;
			}
		}
		if (planets == 0) {
			for (Ship s : gameMap.getAllShips()) {
				if (s.getOwner() != gameMap.getMyPlayerId() && s.getDockingStatus() != DockingStatus.Undocked) {
					planets = 1;
					break;
				}
			}
		}
		return planets;
	}

	private int appropriateSpeed(Ship ship, int numShipsAround) {
		//if (isLeader(ship)) return 6;
		return Constants.MAX_SPEED;
	}

	private int getGoing(Integer id) {
		if (numGoing.get(id) == null)
			return 0;
		else
			return numGoing.get(id);
	}

	private void addGoing(Integer id) {
		if (numGoing.containsKey(id)) {
			Integer newValue = numGoing.get(id) + 1;
			numGoing.remove(id);
			numGoing.put(id, newValue);
		} else {
			numGoing.put(id, 1);
		}
	}
	
	private void addNewPosition(ThrustMove tm) {
		Ship ship = tm.getShip();
		MovingShip ms = new MovingShip(ship, tm.getThrust(), tm.getAngle());
		movingShips.add(ms);
		SquadLeader sl = getLeader(ship);
		if (sl != null) {
			sl.updateThrust(tm, turnNum);
			sl.newestPosition = ms.posAfterThisTurn;
		}
	}
	
	private MovingShip getMovingShip(Ship s) {
		Boolean moved = didShipsTurn.get(s.getId());
		if (moved != null && moved == true) {
			for (MovingShip ms: movingShips) {
				if (ms.getId() == s.getId()) {
					return ms;
				}
			}
		}
		return null;
	}
	
	public static class MovingShip extends Ship {
		public final int thrust, angleDeg;
		public final Position posAfterThisTurn;
		public MovingShip(Ship s, int thrust, int angleDeg) {
			super(s.getOwner(), s.getId(), s.getXPos(), s.getYPos(), s.getHealth(), s.getDockingStatus(), s.getDockedPlanet(), s.getDockingProgress(), s.getWeaponCooldown());
			this.thrust = thrust;
			this.angleDeg = angleDeg;
			
			double angleRad = Math.toRadians(angleDeg);
			posAfterThisTurn = new Position(s.getXPos() + thrust * Math.cos(angleRad), s.getYPos() + thrust * Math.sin(angleRad));
		}
	}

	private Entity getObjectBetween(Position start, Position target, double fudge, ArrayList<Entity> entities,
			int thrust, boolean fatEnemy) {
		return getObjectBetween(start, target, fudge, entities, thrust, fatEnemy, null, false);
	}
	private Entity getObjectBetweenInGroup(ArrayList<Ship> group, double angleRad, double fudge, ArrayList<Entity> entities,
			int thrust, boolean fatEnemy, Ship notFat, boolean strictFat) {
		for (Ship s: group) {
			Position target = new Position(s.getXPos() + Math.cos(angleRad) * (thrust+1), s.getYPos() + Math.sin(angleRad) * (thrust+1));
			Entity e = getObjectBetween(s, target, fudge, entities, thrust, fatEnemy, notFat, strictFat);
			if (e != null) return e;
		}
		return null;
	}
	private Entity getObjectBetween(Position start, Position target, double fudge, ArrayList<Entity> entities,
			int thrust, boolean fatEnemy, Ship notFat, boolean strictFat) {
		ArrayList<Entity> moveableBetw = new ArrayList<>();
		for (Entity e : entities) {
			if (e.equals(start) || ((e.equals(target) && target instanceof Entity)))
				continue;
			if (e instanceof MovingShip) {
				MovingShip ms = (MovingShip)e;
				if (ms != null && doesShipCollide(start, start.orientTowardsInRad(target), thrust, fudge, ms))
					return ms;
				
			} else {
				if (fatEnemy && e instanceof Ship && e.getOwner() != gameMap.getMyPlayerId()
						&& (strictFat || start.getDistanceTo(e) > (notFat == null ? WEAPON_RADIUS + 1: 0))
						&& (strictFat || ((Ship) e).getDockingStatus() == DockingStatus.Undocked)
						&& (notFat == null || notFat != (Ship)e)) {
					double radius = (notFat == null ? WEAPON_RADIUS + 2 : WEAPON_RADIUS);
					if (strictFat) radius = WEAPON_RADIUS;
					if (notFat != null && e.getDistanceTo(target) < WEAPON_RADIUS) return e;
					if (notFat != null && e.getDistanceTo(start) < WEAPON_RADIUS) radius = Constants.SHIP_RADIUS;
					if (Collision.segmentCircleIntersect(start, target,
							new Entity(0, 0, e.getXPos(), e.getYPos(), 0, radius), fudge, false))
						return e;
				} else if (e instanceof Ship && e.getOwner() == gameMap.getMyPlayerId()) {
					Boolean moved = didShipsTurn.get(e.getId());
					Boolean inQueue = inMoveQueue.get(e.getId());
					if ((moved == null || moved == false) && (inQueue == null || inQueue == false)) {
						if (Collision.segmentCircleIntersect(start, target, e, fudge, false)) {
							moved = movedShip.get(e.getId());
							if (moved != null && moved == true) {
								moveableBetw.add(e);
								continue;
							}
						}
					}
				}
				if (Collision.segmentCircleIntersect(start, target, e, fudge, false))
					return e;
			}
		}
		/*for (Entity e : moveableBetw) {
			EntityNode temp = sortedEntities;
			moveShip((Ship) e, 1, false);
			inMoveQueue.put(e.getId(), false);
			sortedEntities = temp;
			MovingShip ms = getMovingShip((Ship)e);
			if (ms != null && doesShipCollide(start, start.orientTowardsInRad(target), thrust, fudge, ms))
				return ms;
		}*/
		return null;
	}
	
	private boolean doesShipCollide(Position start, double angleRad, int thrust, double fudge, MovingShip ms) {
		double dx = start.getXPos() - ms.getXPos();
	    double dy = start.getYPos() - ms.getYPos();
	    
	    Position vel1 = new Position(thrust * Math.cos(angleRad), thrust * Math.sin(angleRad));
	    double angleRad2 = Math.toRadians(ms.angleDeg);
	    Position vel2 = new Position(ms.thrust * Math.cos(angleRad2), ms.thrust * Math.sin(angleRad2));
	    
	    double dvx = vel1.getXPos() - vel2.getXPos();
	    double dvy = vel1.getYPos() - vel2.getYPos();

	    // Quadratic formula
	    double a = square(dvx) + square(dvy);
	    double b = 2 * (dx * dvx + dy * dvy);
	    double c = square(dx) + square(dy) - square(ms.getRadius() + fudge);

	    double disc = square(b) - 4 * a * c;

	    if (a == 0.0) {
	        if (b == 0.0) {
	            if (c <= 0.0) {
	                // Implies r^2 >= dx^2 + dy^2 and the two are already colliding
	                return true;
	            }
	            return false;
	        }
	        double t = -c / b;
	        if (t >= 0.0 && t <= 1.0) {
	            return true;
	        }
	        return false;
	    }
	    else if (disc == 0.0) {
	        // One solution
	        double t = -b / (2 * a);
	        if (t >= 0.0 && t <= 1.0)
	        	return true;
	        return false;
	    }
	    else if (disc > 0) {
	        double t1 = -b + Math.sqrt(disc);
	        double t2 = -b - Math.sqrt(disc);

	        if (t1 >= 0.0 && t2 >= 0.0) {
	        	double t = Math.min(t1, t2) / (2 * a);
	        	if (t >= 0.0 && t <= 1.0)
	        		return true;
	            return false;
	        } else if (t1 <= 0.0 && t2 <= 0.0) {
	        	double t = Math.max(t1, t2) / (2 * a);
	        	if (t >= 0.0 && t <= 1.0)
	        		return true;
	            return false;
	        } else {
	            return true;
	        }
	    }
	    else {
	        return false;
	    }
	}

	private Entity getObjectBetweenExtensive(Position start, Position target, double fudge, ArrayList<Entity> entities,
			int thrust, int okayToAttack, boolean assumeShipsMoveStraight) {
		for (Entity e : entities) {
			if (e.equals(start) || ((e.equals(target) && target instanceof Entity)))
				continue;
			if (e instanceof MovingShip) {
				MovingShip ms = (MovingShip)e;
				if (ms != null && doesShipCollide(start, start.orientTowardsInRad(target), thrust, fudge, ms))
					return ms;
			} else {
				if (e instanceof Ship && ((Ship) e).getOwner() != gameMap.getMyPlayerId()
						&& ((Ship) e).getDockingStatus() == DockingStatus.Undocked
						&& e.getOwner() != okayToAttack) {
					if (e.getDistanceTo(start) > WEAPON_RADIUS + fudge) {
						boolean result = canCollideWithLine((Ship) e, start, target, thrust, fudge, assumeShipsMoveStraight);
						if (result)
							return e;
					} else {
						if (isWithinRad(start.orientTowardsInRad(target), start.orientTowardsInRad(e), Math.PI / 8.0))
							return e;
					}
				}
				if (Collision.segmentCircleIntersect(start, target, e, fudge, false))
					return e;
			}
		}

		return null;
	}

	private boolean canCollideWithLine(Ship ship, Position start, Position end, int thrust, double fudge, boolean assumeMoveStraight) {
		if (assumeMoveStraight) {
			Ship tmp = new Ship(gameMap.getMyPlayerId(), -1, start.getXPos(), start.getYPos(), 1, DockingStatus.Undocked, 0, 0, 0);
			MovingShip movingShip = new MovingShip(tmp, thrust, Util.angleRadToDegClipped(start.orientTowardsInRad(end)));
			for (int newThrust = 0; newThrust <= 7; newThrust++) {
				if (doesShipCollide(ship, ship.orientTowardsInRad(start), newThrust, WEAPON_RADIUS + fudge, movingShip)) {
					return true;
				}
			}
			return false;
		}
		double dist = start.getDistanceTo(end);
		double dx = (end.getXPos() - start.getXPos()) / dist, dy = (end.getYPos() - start.getYPos()) / dist;
		for (int i = 0; i <= 14; i++) {
			double t = i / 14.0 * thrust;
			Position p = new Position(start.getXPos() + t * dx, start.getYPos() + t * dy);
			if (ship.getDistanceTo(p) < WEAPON_RADIUS + fudge + i / 14.0 * 7)
				return true;
		}
		return false;
	}
	
	private boolean canReachEnemy(Ship ship, Ship target) {
		int thrust = 7;
		if (ship.getDistanceTo(target) < 7) thrust = (int)(ship.getDistanceTo(target) - 0.8);
		return !(getObjectBetweenExtensive(ship, target, FUDGE_FACTOR, getEntitiesCloseTo(ship, 5), thrust, -1, false) instanceof Ship);
	}

	private static double square(final double num) {
		return num * num;
	}

	private boolean lockOn(Ship ship, Position target, boolean kamikaze) {
		return lockOn(ship, target, 0, kamikaze, kamikaze ? 0 : WEAPON_RADIUS / 3.0 * 2.0, 7, null);
	}

	private boolean lockOn(Ship ship, Position target, int numEnemies, int numSupporters) {
		return lockOn(ship, target, numEnemies + numSupporters, /* kamikaze */false, WEAPON_RADIUS / 3.0 * 2.0, 7, null);
	}
	
	private boolean lockOnGroup(Ship ship, Position target, int numEnemies, int numSupporters, int maxThrust, ArrayList<Ship> group) {
		return lockOn(ship, target, numEnemies + numSupporters, false, WEAPON_RADIUS / 2.0, maxThrust, group);
	}
	
	private boolean lockOn(Ship ship, Position target, int numShipsAround, boolean kamikaze, double gap, int maxThrust, ArrayList<Ship> group) {
		return lockOn(ship, target, numShipsAround, kamikaze, gap, maxThrust, group, true);

	}
	private boolean lockOnAvoidEnemy(Ship ship, Position target, int numShipsAround, boolean kamikaze, double gap, int maxThrust, int okayToAttack) {
		logEntry("\tlockOnAvoidEnemy called. Kamikaze = " + kamikaze + ", gap = " + gap);
		final ThrustMove newThrustMove = navAvoidEnemy(ship, maxThrust, NUM_CORRECTIONS, ANGLE_DELTA, roundRad(ship.orientTowardsInRad(target)), 0, ship.getDistanceTo(target), okayToAttack);
		if (newThrustMove != null) {
			submitThrustMove(newThrustMove);
			if (target instanceof Entity)
				setLeadersTarget(ship, (Entity)target);
			return true;
		}
		return false;
	}
	private boolean lockOn(Ship ship, Position target, int numShipsAround, boolean kamikaze, double gap, int maxThrust, ArrayList<Ship> group, boolean adjustPosIfNecessary) {
		logEntry("\tlockOn called. Kamikaze = " + kamikaze + ", gap = " + gap);
		if (group == null || !group.contains(ship)) {
			if (group == null)
				group = new ArrayList<>();
			group.add(ship);
		}
		if (!kamikaze && target instanceof Entity && ship.getDistanceTo(target) <= WEAPON_RADIUS) {
			logEntry("not moving because close enough");
			moveList.add(new ThrustMove(ship, 0, 0));
			setLeadersTarget(ship, (Entity)target);
			didShipsTurn.put(ship.getId(), true);
			return true;
		}
		int speed = kamikaze ? maxThrust : Math.min(appropriateSpeed(ship, numShipsAround), maxThrust);
		if (target instanceof Entity && ((Entity)target).getOwner() == gameMap.getMyPlayerId()) {
			logEntry("\ttried to lock on to my own object. Rejected");
			return false;
		}
		double angularStepRad = ANGLE_DELTA;
		int maxCorrections = NUM_CORRECTIONS;
		int angleChanges = 0;
		Position targetPos = target;
		if (target instanceof Ship && !kamikaze) {
			Ship myClosestDockedShip = getMyClosestDockedShip((Ship)target);
			if (myClosestDockedShip != null && target.getDistanceTo(myClosestDockedShip) < 10) {
				// Assume it's moving towards the docked ship.
				logEntry("I think it's moving toward my docked ship " + myClosestDockedShip.getId());
				logEntry("Therefore, I'm moving there");
				targetPos = new Position(myClosestDockedShip.getXPos(), myClosestDockedShip.getYPos());
				gap = 1.5;
			} 
			else if (myClosestDockedShip != null && target.getDistanceTo(myClosestDockedShip) < 35 && !isWithinRad(target.orientTowardsInRad(myClosestDockedShip), target.orientTowardsInRad(ship), Math.PI / 8)) {
				logEntry("shifting target closer to ship " + myClosestDockedShip.getId());
				targetPos = createTarget((Ship)target, target.orientTowardsInRad(myClosestDockedShip), 4);
				gap = 1.5;
			} else if (ship.getDistanceTo(target) < 14 && !kamikaze && adjustPosIfNecessary) {
				logEntry("adjusting target position");
				int numCorrections = ship.getDistanceTo(target) < 7 ? 3 : 2;
				maxCorrections -= numCorrections;
				double rad = ship.orientTowardsInRad(targetPos);
				targetPos = new Position(ship.getXPos() + Math.cos(rad + angularStepRad * numCorrections) * 14,
						ship.getYPos() + Math.sin(rad + angularStepRad * numCorrections) * 14);
			}
		}
		final ThrustMove newThrustMove = navGroup(ship, targetPos, speed, maxCorrections,
				angularStepRad, kamikaze ? 0 : gap, false, angleChanges, group);
		if (newThrustMove != null) {
			submitThrustMove(newThrustMove);
			if (target instanceof Entity)
				setLeadersTarget(ship, (Entity)target);
			return true;
		}
		return false;
	}
	
	private boolean lockOnMagnet(Ship ship, Position target) {
		return lockOnMagnet(ship, target, WEAPON_RADIUS / 3.0 * 2.0);
	}
	
	private boolean lockOnMagnet(Ship ship, Position target, double gap) {
		return lockOnMagnet(ship, target, gap, Constants.MAX_SPEED, null);
	}
	
	private boolean lockOnMagnet(Ship ship, Position target, double gap, int maxThrust, ArrayList<Ship> group) {
		if (group == null) {
			group = new ArrayList<>();
			group.add(ship);
		}
		Ship enemyShip = null;
		if (target instanceof Ship && ((Ship)target).getOwner() != gameMap.getMyPlayerId() && ((Ship)target).getId() >= 1) {
			enemyShip = (Ship)target;
		}
		else {
			enemyShip = getClosestEnemy(ship);
		}
		if (ship.getDistanceTo(enemyShip) > 7) {
			double angleRad = roundRad(ship.orientTowardsInRad(target));
			Position pivot = createTarget(ship, angleRad, maxThrust);
			MovingShip ms = getClosestMovingShip(pivot);
			if (ms != null && ms.posAfterThisTurn != null && ms.thrust != 0 && ms.posAfterThisTurn.getDistanceTo(pivot) < 5 && isWithinRad(angleRad, ship.orientTowardsInRad(ms.posAfterThisTurn), Math.PI / 6.0)) {
				int wouldBeThrust = (int)Math.round(ship.getDistanceTo(ms.posAfterThisTurn));
				if (wouldBeThrust <= 7 && (wouldBeThrust >= 3 || maxThrust <= 3)) {
					ArrayList<Entity> entities = getEntitiesCloseTo(ship, 5, group);
					for (int i = 2; i <= 6; i++) {
						for (int j = -1; j <= 1; j += 2) {
							Position try1 = createTarget(ms.posAfterThisTurn, ms.posAfterThisTurn.orientTowardsInRad(enemyShip) + Math.PI / 2 * j, i * 0.55);
							if (ship.getDistanceTo(try1) < 7.6) {
								int thrust = (int)Math.min(7, Math.round(ship.getDistanceTo(try1)));
								double angle = roundRad(ship.orientTowardsInRad(try1));
								if (getObjectBetweenInGroup(group, angle, FUDGE_FACTOR, entities, thrust, false, null, false) == null && !isOffMap(ship, angle, thrust)) {
									ThrustMove tm = new ThrustMove(ship, (Util.angleRadToDegClipped(angle) + 360) % 360, thrust);
									submitThrustMove(tm);
									return true;
								}
							}
						}
					}
					// didn't find an exceptional position
					/*if (ms.posAfterThisTurn.getDistanceTo(ship) < 3) {
						ThrustMove tm = navDualDir(ship, wouldBeThrust, NUM_CORRECTIONS, ANGLE_DELTA, ship.orientTowardsInRad(ms.posAfterThisTurn), 0, ms.posAfterThisTurn.getDistanceTo(ship), getEntitiesCloseTo(ship, 5), false);
						if (tm != null) {
							submitThrustMove(tm);
							return true;
						}
					}*/
				}
			}
		}
		return lockOn(ship, target, 0, false, gap, maxThrust, group, true);
	}

	private ArrayList<Entity> getEntitiesCloseTo(final Ship ship, final double buffer) {
		return getEntitiesCloseTo(ship, buffer, new ArrayList<Ship>());
	}
	private ArrayList<Entity> getEntitiesCloseTo(final Ship ship, final double buffer, ArrayList<Ship> dontAdd) {
		ArrayList<Entity> beingsInRange = new ArrayList<>();
		String inRangeEntry = "in range: ";
		for (EntityNode curr = sortedEntities; curr != null; curr = curr.getNext()) {
			Entity e = curr.getEntity();
			if (isInList(dontAdd, e)) continue;
			double d = curr.getDistance();
			if (e == null)
				continue;

			if (d < ship.getRadius() + e.getRadius() + 7 + buffer) {
				Boolean wasMoved = movedShip.get(e.getId());
				if (!(e instanceof Ship) || (wasMoved == null || wasMoved == false)) {
					beingsInRange.add(e);
					if (debugThisFrame) {
						char c = 'E';
						if (e instanceof Ship) c = 'S';
						else if (e instanceof Planet) c = 'P';
						inRangeEntry += c + e.getId() + " ";
					}
				}
			}
		}
		for (MovingShip ms: movingShips) {
			if (isInList(dontAdd, ms)) continue;
			if (ms.getDistanceTo(ship) < ship.getRadius() + ms.getRadius() + 7 + ms.thrust + buffer) {
				beingsInRange.add(ms);
				if (debugThisFrame) {
					inRangeEntry += 'M' + ms.getId() + " ";
				}
			}
		}
		logEntry(inRangeEntry);
		return beingsInRange;
	}
	
	private boolean isInList(ArrayList<Ship> ships, Entity q) {
		if (ships == null) return false;
		if (q instanceof Ship) {
			for (Ship s: ships) {
				if (s.getId() == q.getId()) return true;
			}
		}
		return false;
	}

	private ThrustMove nav(final Ship ship, final Position targetPos, final int maxThrust,
			final int maxCorrections, final double angularStepRad, final double gap, boolean fatEnemy) {
		return nav(ship, targetPos, maxThrust, maxCorrections, angularStepRad, gap, fatEnemy, 0);
	}
	
	private ThrustMove nav(final Ship ship, final Position targetPos, final int maxThrust,
			final int maxCorrections, final double angularStepRad, final double gap, boolean fatEnemy, int angleChanges) {
		ArrayList<Ship> group = new ArrayList<>();
		group.add(ship);
		return navGroup(ship, targetPos, maxThrust, maxCorrections, angularStepRad, gap, fatEnemy, angleChanges, group);
	}
	private ThrustMove navGroup(final Ship ship, final Position targetPos, final int maxThrust,
			final int maxCorrections, final double angularStepRad, final double gap, boolean fatEnemy, int angleChanges, ArrayList<Ship> group) {
		final double buffer = 5;
		ArrayList<Entity> beingsInRange = getEntitiesCloseTo(ship, buffer, group);
		if (gap == 0 && targetPos instanceof Ship) {
			for (int i = beingsInRange.size() - 1; i >= 0; i--) {
				if (beingsInRange.get(i).equals(targetPos))
					beingsInRange.remove(i);
			}
		}
		return navGroup(ship, targetPos, maxThrust, true, maxCorrections, angularStepRad, gap,
				beingsInRange, fatEnemy, angleChanges, group);
	}

	private ThrustMove navGroup(final Ship ship, final Position targetPos, int maxThrust,
			final boolean avoidObstacles, final int maxCorrections, final double angularStepRad, final double gap,
			final ArrayList<Entity> beingsInRange, boolean fatEnemy, int angleChanges, ArrayList<Ship> group) {
		final double distance = Math.max(0, ship.getDistanceTo(targetPos));
		if (distance <= gap || maxThrust == 0) {
			return new ThrustMove(ship, 0, 0);
		}
		final double angleRad = ship.orientTowardsInRad(targetPos);

		int thrust = maxThrust;
		if (distance - gap < maxThrust) {
			thrust = (int) (distance - gap);
		}
		double initialAngleRad = roundRad(angleRad);
		Position target = new Position(ship.getXPos() + Math.cos(initialAngleRad) * thrust, ship.getYPos() + Math.sin(initialAngleRad) * thrust);
		
		return navDualDirGroup(ship, thrust, maxCorrections, angularStepRad, initialAngleRad, angleChanges, distance,
				beingsInRange, fatEnemy, group, target);
	}

	// This function requires that initialAngleRad is rounded to the nearest degree (not radian)
	private ThrustMove navDualDir(final Ship ship, int thrust, final int maxCorrections,
			final double angularStepRad, final double initialAngleRad, final int angleChanges, final double distance,
			final ArrayList<Entity> beingsInRange, boolean fatEnemy) {
		ArrayList<Ship> group = new ArrayList<>();
		group.add(ship);
		Position target = new Position(ship.getXPos() + Math.cos(initialAngleRad) * thrust, ship.getYPos() + Math.sin(initialAngleRad) * thrust);
		return navDualDirGroup(ship, thrust, maxCorrections, angularStepRad,
				initialAngleRad, angleChanges, distance, beingsInRange, fatEnemy, group, target);
	}
	
	private ThrustMove navDualDirGroup(final Ship ship, int thrust, final int maxCorrections,
			final double angularStepRad, final double initialAngleRad, final int angleChanges, final double distance,
			final ArrayList<Entity> beingsInRange, boolean fatEnemy, ArrayList<Ship> group, Position target) {
		if (maxCorrections <= 0) {
			return null;
		}
		// +1
		double angleRad1 = initialAngleRad + angularStepRad * angleChanges;
		int thrust1 = 0, thrust2 = 0;
		Entity betw = null;
		for (int t = thrust; t > 0; t--) {
			betw = getObjectBetweenInGroup(group, angleRad1, FUDGE_FACTOR, beingsInRange, t, fatEnemy, null, false);
			if (betw == null && !isAnyOffMap(group, angleRad1, t)) {
				thrust1 = t;
				break;
			}
		}
		// -1
		double angleRad2 = initialAngleRad - angularStepRad * angleChanges;
		for (int t = thrust; t > 0; t--) {
			betw = getObjectBetweenInGroup(group, angleRad2, FUDGE_FACTOR, beingsInRange, t, fatEnemy, null, false);
			if (betw == null && !isAnyOffMap(group, angleRad2, t)) {
				thrust2 = t;
				break;
			}
		}
		// determine best of the two sides.
		double angleRad = angleRad1;
		int bestThrust = thrust1;
		if (thrust2 > thrust1) {
			angleRad = angleRad2;
			bestThrust = thrust2;
		}
		else if (thrust2 == thrust1) {
			// tiebreaker 1
			Position p1 = createTarget((Position)ship, angleRad1, thrust1);
			Position p2 = createTarget((Position)ship, angleRad2, thrust2);
			Ship ship1 = getMyClosestDockedShip(p1);
			Ship ship2 = getMyClosestDockedShip(p2);
			double score1 = (ship1 == null) ? Double.MAX_VALUE : p1.getDistanceTo(ship1);
			double score2 = (ship2 == null) ? Double.MAX_VALUE : p2.getDistanceTo(ship2);
			if (score1 > SUPPORTER_SIGHT && score2 > SUPPORTER_SIGHT) {
				// tiebreaker 2
				Position s1 = getClosestShipPoint(p1, ship);
				Position s2 = getClosestShipPoint(p2, ship);
				score1 = (s1 == null) ? Double.MAX_VALUE : p1.getDistanceTo(s1);
				score2 = (s2 == null) ? Double.MAX_VALUE : p2.getDistanceTo(s2);
				if (score1 > SUPPORTER_SIGHT && score2 > SUPPORTER_SIGHT) {
					// tiebreaker 3: random
					if (rand.nextBoolean()) {
						angleRad = angleRad2;
						bestThrust = thrust2;
					}
				}
				else if (score2 < score1) {
					angleRad = angleRad2;
					bestThrust = thrust2;
				}
			}
			else if (score2 < score1) {
				angleRad = angleRad2;
				bestThrust = thrust2;
			}
		}
		// calculate the best from the rest of the angles
		int moreCorrections = bestThrust == 0 ? maxCorrections - 1 : Math.min(maxCorrections - 1,(int)((thrust - bestThrust) * (Math.PI / 4.0) / angularStepRad));
		ThrustMove tmOther = navDualDirGroup(ship, thrust, moreCorrections, angularStepRad, initialAngleRad, angleChanges+1, distance,
				beingsInRange, fatEnemy, group, target);
		if (tmOther == null) tmOther = new ThrustMove(ship, 0, 0);
		
		// check which one is better.
		Position p1 = new Position(ship.getXPos() + Math.cos(angleRad) * bestThrust, ship.getYPos() + Math.sin(angleRad) * bestThrust);
		double angleRadOther = Math.toRadians(tmOther.getAngle());
		double thrustOther = tmOther.getThrust();
		Position p2 = new Position(ship.getXPos() + Math.cos(angleRadOther) * thrustOther, ship.getYPos() + Math.sin(angleRadOther) * thrustOther);
		if ((p2.getDistanceTo(target) < p1.getDistanceTo(target) || (bestThrust <= 2 && thrustOther > 2)) && thrustOther != 0)
			return tmOther;
		
		final int angleDeg = Util.angleRadToDegClipped(angleRad);
		logEntry("decided angle " + angleDeg);
		return new ThrustMove(ship, (angleDeg + 360) % 360, bestThrust);
	}

	private ThrustMove navAvoidEnemy(Ship ship, int thrust, int maxCorrections, double angularStepRad,
			double initialAngleRad, int angleChanges, double distance, int okayToAttack) {
		ArrayList<Entity> beingsInRange = getEntitiesCloseTo(ship, 10);
		initialAngleRad = roundRad(initialAngleRad);
		ThrustMove tm = navAvoidEnemy(ship, thrust, maxCorrections, angularStepRad,
				initialAngleRad, angleChanges, distance, beingsInRange, true, okayToAttack, false);
		if (tm != null)
			return tm;
		tm = navAvoidEnemy(ship, thrust, maxCorrections, angularStepRad,
				initialAngleRad, angleChanges, distance, beingsInRange, true, okayToAttack, true);
		if (tm != null)
			return tm;
		tm = navDualDir(ship, thrust, maxCorrections, angularStepRad, initialAngleRad,
				angleChanges, distance, beingsInRange, true);
		if (tm != null)
			return tm;
		return navDualDir(ship, thrust, maxCorrections, angularStepRad, initialAngleRad,
				angleChanges, distance, beingsInRange, false);
	}

	private ThrustMove navAvoidEnemy(final Ship ship, int thrust, final int maxCorrections,
			final double angularStepRad, final double initialAngleRad, final int angleChanges, final double distance,
			final ArrayList<Entity> beingsInRange, boolean allowNearingEdge, int okayToAttack, boolean assumeShipsMoveStraight) {
		if (maxCorrections <= 0) {
			return null;
		}

		// +1
		double angleRad1 = initialAngleRad + angularStepRad * angleChanges;
		int thrust1 = 0;
		Position targetPos = new Position(ship.getXPos() + Math.cos(angleRad1) * (thrust+1),
				ship.getYPos() + Math.sin(angleRad1) * (thrust+1));
		Entity betw = null;
		betw = getObjectBetweenExtensive(ship, targetPos, FUDGE_FACTOR, beingsInRange, thrust, okayToAttack, assumeShipsMoveStraight);
		
		if (isOnMap(Math.cos(angleRad1) * thrust + ship.getXPos(), Math.sin(angleRad1) * thrust + ship.getYPos())
				&& (betw == null)) {
			thrust1 = thrust;
		}
			// -1
		double angleRad2 = initialAngleRad - angularStepRad * angleChanges;
		int thrust2 = 0;
		targetPos = new Position(ship.getXPos() + Math.cos(angleRad2) * (thrust+1),
				ship.getYPos() + Math.sin(angleRad2) * (thrust+1));
		betw = null;
		betw = getObjectBetweenExtensive(ship, targetPos, FUDGE_FACTOR, beingsInRange, thrust, okayToAttack, assumeShipsMoveStraight);
		if (isOnMap(Math.cos(angleRad2) * thrust + ship.getXPos(), Math.sin(angleRad2) * thrust + ship.getYPos())
				&& (betw == null)) {
			thrust2 = thrust;
		}
		double angleRad = angleRad1;
		// decide what move to make.
		if (thrust1 == 0 && thrust2 == 0) {
			return navAvoidEnemy(ship, thrust, maxCorrections - 1, angularStepRad,
					initialAngleRad, angleChanges + 1, distance, beingsInRange, allowNearingEdge,
					okayToAttack, assumeShipsMoveStraight);
		}
		boolean angle1OnMap = isOnMap(Math.cos(angleRad1) * 30 + ship.getXPos(), Math.sin(angleRad1) * 30 + ship.getYPos());
		boolean angle2OnMap = isOnMap(Math.cos(angleRad2) * 30 + ship.getXPos(), Math.sin(angleRad2) * 30 + ship.getYPos());
		if ((thrust1 != 0 && thrust2 == 0 && !angle1OnMap) ||
				(thrust2 != 0 && thrust1 == 0 && !angle2OnMap)) {
			if (!allowNearingEdge) return null;
			ThrustMove tm = navAvoidEnemy(ship, thrust, maxCorrections - 1, angularStepRad,
					initialAngleRad, angleChanges + 1, distance, beingsInRange, false,
					okayToAttack, assumeShipsMoveStraight);
			if (tm != null)
				return tm;
			if (thrust1 == 0) angleRad = angleRad2;
		}
		else if ((thrust1 == 0 && thrust2 != 0)) {
			angleRad = angleRad2;
		}
		else if (thrust1 != 0 && thrust2 != 0) {
			if (!angle1OnMap && !angle2OnMap) {
				if (!allowNearingEdge) return null;
				ThrustMove tm = navAvoidEnemy(ship, thrust, maxCorrections - 1, angularStepRad,
						initialAngleRad, angleChanges + 1, distance, beingsInRange, false,
						okayToAttack, assumeShipsMoveStraight);
				if (tm != null)
					return tm;
				angleRad = angleRad1;
			}
			else if (!angle1OnMap) {
				angleRad = angleRad2;
			}
			else if (!angle2OnMap) {
				angleRad = angleRad1;
			}
		}
		
		final int angleDeg = Util.angleRadToDegClipped(angleRad);
		logEntry("decided angle " + angleDeg);
		return new ThrustMove(ship, (angleDeg + 360) % 360, thrust);
	}
	
	private ThrustMove navSingleTarget(Ship ship, Ship target) {
		ArrayList<Ship> group = new ArrayList<>();
		group.add(ship);
		return navSingleTargetGroup(ship, target, group);
	}
	
	private ThrustMove navSingleTargetGroup(Ship ship, Ship target, ArrayList<Ship> group) {
		logEntry("navigateSingleTarget called. Target = " + target.getId());
		if (ship.getDistanceTo(target) > WEAPON_RADIUS + 7) return null;
		double initRad = roundRad(ship.orientTowardsInRad(target));
		if (ship.getDistanceTo(target) < WEAPON_RADIUS) initRad = (initRad + Math.PI) % (Math.PI * 2);
		return navSingleTargetGroup(ship, target, 7, NUM_CORRECTIONS, ANGLE_DELTA,
				initRad, 0, getEntitiesCloseTo(ship, 5 + WEAPON_RADIUS, group), group);
	}
	
	private ThrustMove navSingleTargetGroup(Ship ship, Ship target, int maxThrust, int corrections,
			double angularStepRad, double initAngleRad, int angleChanges, ArrayList<Entity> beingsInRange,
			ArrayList<Ship> group) {
		if (corrections <= 0) {
			return null;
		}
		double angleRad = initAngleRad + angularStepRad * angleChanges;
		int thrust = maxThrust;
		int acceptedThrust = 0, maxAcceptedThrust = 0;
		 while(thrust > 0) {
			Position newAnticipated = createTarget(ship, angleRad, thrust);
			if (newAnticipated.getDistanceTo(target) < WEAPON_RADIUS) {
				acceptedThrust = thrust;
				if (maxAcceptedThrust == 0) maxAcceptedThrust = thrust;
			}
			thrust--;
		}
		thrust = acceptedThrust;
		if (ship.getDistanceTo(target) < WEAPON_RADIUS) thrust = maxAcceptedThrust;
		Entity betw = null;
		
		if (thrust != 0) {
			// +1
			betw = getObjectBetweenInGroup(group, angleRad, FUDGE_FACTOR, beingsInRange, thrust, true, target, true);
		}
		if (thrust == 0 || (isAnyOffMap(group, angleRad, thrust)
				|| (betw != null))) {
			// -1
			angleRad = initAngleRad - angularStepRad * angleChanges;
			thrust = maxThrust;
			acceptedThrust = 0;
			maxAcceptedThrust = 0;
			 while(thrust > 0) {
				Position newAnticipated = createTarget(ship, angleRad, thrust);
				if (newAnticipated.getDistanceTo(target) < WEAPON_RADIUS) {
					acceptedThrust = thrust;
					if (maxAcceptedThrust == 0) maxAcceptedThrust = thrust;
				}
				thrust--;
			}
			thrust = acceptedThrust;
			if (ship.getDistanceTo(target) < WEAPON_RADIUS) thrust = maxAcceptedThrust;
			
			if (thrust != 0) {
				betw = null;
				betw = getObjectBetweenInGroup(group, angleRad, FUDGE_FACTOR, beingsInRange, thrust, true, target, true);
			}
			if (thrust == 0 || (isAnyOffMap(group, angleRad, thrust)
					|| (betw != null))) {
				return navSingleTargetGroup(ship, target, thrust, corrections - 1, angularStepRad,
						initAngleRad, angleChanges + 1, beingsInRange, group);
			}
		}

		final int angleDeg = Util.angleRadToDegClipped(angleRad);
		logEntry("decided angle " + angleDeg);
		return new ThrustMove(ship, (angleDeg + 360) % 360, thrust);
	}

	private ThrustMove navToDock(final Ship ship, final Planet dockTarget) {
		if (ship.getDistanceTo(dockTarget) > 8 + dockTarget.getRadius()) {
			final Position targetPos = getClosestPoint(ship, dockTarget);
			return nav(ship, targetPos, 7, NUM_CORRECTIONS, ANGLE_DELTA, 0, false);
		}
		final double buffer = 5;
		ArrayList<Entity> beingsInRange = getEntitiesCloseTo(ship, buffer, null);
		return navToDock(ship, dockTarget, NUM_CORRECTIONS, ANGLE_DELTA, 
				roundRad(ship.orientTowardsInRad(dockTarget)), 0, beingsInRange);
	}
	
	private ThrustMove navToDock(Ship ship, Planet target, int correctionsLeft, 
			double angleDelta, double initAngleRad, int correctionsMade, ArrayList<Entity> entities) {
		if (correctionsLeft <= 0) return null;
		// +1
		double angleRad1 = initAngleRad + angleDelta * correctionsMade;
		int thrust1 = 0, thrust2 = 0;
		Entity betw = null;
		for (int t = Constants.MAX_SPEED; t > 0; t--) {
			betw = getObjectBetween(ship, createTarget((Position)ship, angleRad1, t), FUDGE_FACTOR, entities, t, false, null, false);
			if (betw == null && !isOffMap(ship, angleRad1, t)) {
				thrust1 = t;
				break;
			}
		}
		// -1
		double angleRad2 = initAngleRad - angleDelta * correctionsMade;
		for (int t = Constants.MAX_SPEED; t > 0; t--) {
			betw = getObjectBetween(ship, createTarget((Position)ship, angleRad2, t), FUDGE_FACTOR, entities, t, false, null, false);
			if (betw == null && !isOffMap(ship, angleRad2, t)) {
				thrust2 = t;
				break;
			}
		}
		// determine best of the two sides.
		double angleRad = angleRad1;
		int bestThrust = thrust1;
		boolean inRange = target.getDistanceTo(createTarget(ship, angleRad, bestThrust)) < target.getRadius() + Constants.DOCK_RADIUS;
		if (!inRange) {
			angleRad = angleRad2;
			bestThrust = thrust2;
			inRange = target.getDistanceTo(createTarget(ship, angleRad, bestThrust)) < target.getRadius() + Constants.DOCK_RADIUS;
			if (!inRange && thrust1 > thrust2) {
				angleRad = angleRad1;
				bestThrust = thrust1;
			}
		}
		// calculate the best from the rest of the angles
		if (!inRange) {
			ThrustMove tmOther = navToDock(ship, target, correctionsLeft - 1, ANGLE_DELTA, initAngleRad, correctionsMade+1,
					entities);
			if (tmOther == null) tmOther = new ThrustMove(ship, 0, 0);
			
			// check which one is better.
			Position p1 = new Position(ship.getXPos() + Math.cos(angleRad) * bestThrust, ship.getYPos() + Math.sin(angleRad) * bestThrust);
			double angleRadOther = Math.toRadians(tmOther.getAngle());
			double thrustOther = tmOther.getThrust();
			Position p2 = new Position(ship.getXPos() + Math.cos(angleRadOther) * thrustOther, ship.getYPos() + Math.sin(angleRadOther) * thrustOther);
			if ((p2.getDistanceTo(target) < p1.getDistanceTo(target) || (bestThrust <= 2 && thrustOther > 2)) && thrustOther != 0)
				return tmOther;
		}
		final int angleDeg = Util.angleRadToDegClipped(angleRad);
		logEntry("decided angle " + angleDeg);
		return new ThrustMove(ship, (angleDeg + 360) % 360, bestThrust);
	}

	private boolean isAnyOffMap(ArrayList<Ship> ships, double angleRad, int thrust) {
		for (Ship s: ships) {
			if (isOffMap(s, angleRad, thrust)) {
				return true;
			}
		}
		return false;
	}
	
	private boolean isOffMap(Ship s, double angleRad, int thrust) {
		return (!isOnMap(s.getXPos() + Math.cos(angleRad) * thrust, s.getYPos() + Math.sin(angleRad) * thrust));
	}
	
	private boolean isOnMap(double x, double y) {
		return !(x <= 1 || y <= 1 || x >= gameMap.getWidth() - 2 || y >= gameMap.getHeight() - 2);
	}

	private Position getClosestPoint(final Ship ship, final Planet target) {
		final double radius = target.getRadius() + Constants.MIN_DISTANCE_FOR_CLOSEST_POINT;
		double angleRad = target.orientTowardsInRad(ship);
		double minDist = Double.MAX_VALUE;
		for (Planet p: gameMap.getAllPlanets().values()) {
			double dist = p.getDistanceTo(target) - p.getRadius() - target.getRadius();
			if (p.isOwned() && p.getOwner() != gameMap.getMyPlayerId() && dist < minDist && dist < ENEMY_PLANET_CLOSE_DIST) {
				minDist = dist;
				angleRad = p.orientTowardsInRad(target);
			}
		}

		angleRad = _getClosestAngle(ship, target, radius, angleRad, ANGLE_DELTA, NUM_CORRECTIONS);

		final double x = target.getXPos() + radius * Math.cos(angleRad);
		final double y = target.getYPos() + radius * Math.sin(angleRad);

		return new Position(x, y);
	}

	private double _getClosestAngle(Ship ship, final Entity target, double radius, double angleRad, double angleRadStep,
			int maxCorrections) {
		if (maxCorrections <= 0)
			return angleRad;
		Position p = new Position(target.getXPos() + radius * Math.cos(angleRad),
				target.getYPos() + radius * Math.sin(angleRad));
		for (Ship s : gameMap.getAllShips()) {
			if (s.equals(ship))
				continue;
			if (p.getDistanceTo(s) < s.getRadius() + ship.getRadius() + 1) {
				return _getClosestAngle(ship, target, radius, angleRad + angleRadStep, angleRadStep,
						maxCorrections - 1);
			}
		}
		return angleRad;
	}

	public static class Surroundings {
		Planet[] planets;
		Ship[] enemyShipsClosest;
		boolean destructorMode = false;
		boolean foundTarget = false;
		Ship target = null;
		Planet goodPlanet = null, myClosestPlanet = null;
		double goodPlanetScore = 0;
		int supporters = 0, closeSupporters = 0, challengers = 0, closeChallengers = 0, protecting = 0, enemiesAttackingMe = 0;
		Ship reallyCloseEnemy = null;
		ArrayList<Ship> possiblePillage = new ArrayList<>();

		public Surroundings(MyBot myBot, Ship ship, GameMap gameMap, EntityNode entities) {
			planets = new Planet[entities.getSize()];
			enemyShipsClosest = new Ship[entities.getSize()];
			int planetI = 0, enemyI = 0;
			double leastMyPlanetDist = Double.MAX_VALUE;
			ArrayList<Clump> clumpsChecked = new ArrayList<>();
			for (EntityNode curr = entities; curr != null; curr = curr.getNext()) {
				Entity e = curr.getEntity();
				double d = curr.getDistance();
				if (e instanceof Planet) {
					Planet p = (Planet) e;
					planets[planetI++] = p;
					if (p.isOwned() && p.getOwner() != gameMap.getMyPlayerId()) {
						if (goodPlanet == null && d > PLANET_TOO_FAR && foundTarget) {
							destructorMode = true;
						}
						if (myBot.isPlanetBetter(p, d, goodPlanet, goodPlanetScore)) {
							goodPlanet = p;
							goodPlanetScore = myBot.scorePlanet(p, d);
						}
					} else if (p.isOwned() && p.getOwner() == gameMap.getMyPlayerId()) {
						if (d < leastMyPlanetDist) {
							leastMyPlanetDist = d;
							myClosestPlanet = p;
						}
					}
					if (!p.isOwned() || (p.getOwner() == gameMap.getMyPlayerId() && p.getDockedShips().size() + myBot.getGoing(p.getId()) < p.getDockingSpots())) {
						double score = myBot.scorePlanet(p, d);
						if (goodPlanet == null || score > goodPlanetScore) {
							goodPlanet = p;
							goodPlanetScore = score;
						}
					}
				} else if (e instanceof Ship) {
					Ship ship1 = (Ship) e;
					if (!foundTarget && ship1.getOwner() != gameMap.getMyPlayerId()) {
						if (d < LOCK_ON_SIGHT) {
							foundTarget = true;
							target = ship1;
						}
						possiblePillage.add(ship1);
					}
					if (ship1.getOwner() != gameMap.getMyPlayerId()) {
						enemyShipsClosest[enemyI++] = ship1;
						if (ship1.getDockingStatus() == DockingStatus.Undocked && d < CHALLENGER_SIGHT) {
							if (d < 7 + WEAPON_RADIUS && reallyCloseEnemy == null)
								reallyCloseEnemy = ship1;
							//Clump c = Clump.get(ship1);
							if (d < 5)
								enemiesAttackingMe++;
							/*if (c == null || !clumpsChecked.contains(c)) {
								challengers += c.getSize();
								if (c.isAnyWithin(ship, 8)) closeChallengers += c.getSize();
								clumpsChecked.add(c);
							}*/
							challengers++;
							if (d < 7) closeChallengers++;
						}

					} else if (ship1.getOwner() == gameMap.getMyPlayerId() && d < SUPPORTER_SIGHT
							&& ship1.getDockingStatus() == DockingStatus.Undocked) {
						/*Clump c = Clump.get(ship1);
						if (c == null || !clumpsChecked.contains(c)) {
							ArrayList<Ship> shipsToCheck = new ArrayList<>();
							shipsToCheck.add(ship1);
							if (c != null) shipsToCheck = c.getShips();
							for (Ship ship2: shipsToCheck) {
								Boolean willShipDock = myBot.willDock.get(ship2.getId());
								if (willShipDock == null || willShipDock == false) {
									supporters += 1;
									if (c.isAnyWithin(ship, 7))
										closeSupporters += 1;
								}
							}
							clumpsChecked.add(c);
						}*/
						Boolean willShipDock = myBot.willDock.get(ship1.getId());
						if (willShipDock == null || willShipDock == false) {
							supporters++;
							if (d < 7) closeSupporters++;
						}
					} else if (ship1.getOwner() == gameMap.getMyPlayerId() && d < SUPPORTER_SIGHT) {
						protecting++;
					}

				}
			}
		}
	}

	public static class SquadLeader {
		Entity target = null;
		int shipId;
		int shipOwner;
		Position newestPosition = null;
		int shipsFollowing = 0;
		int shipsFollowingThisTurn = 0;
		int shipsVeryClose = 0;
		int shipsVeryCloseThisTurn = 0;
		
		int lastThrustUpdate = -1;
		int thrust = 0, angle = 0;

		public SquadLeader(Ship s) {
			shipId = s.getId();
			shipOwner = s.getOwner();
			newestPosition = new Position(s.getXPos(), s.getYPos());
		}

		public void nextTurn() {
			shipsFollowing = shipsFollowingThisTurn;
			shipsFollowingThisTurn = 0;
			shipsVeryClose = shipsVeryCloseThisTurn;
			shipsVeryCloseThisTurn = 0;
		}
		
		public void updateThrust(ThrustMove tm, int turnNum) {
			lastThrustUpdate = turnNum;
			thrust = tm.getThrust();
			angle = tm.getAngle();
		}
		
		public int getThrustThisTurn(int turnNum) {
			if (turnNum == lastThrustUpdate) return thrust;
			return 0;
		}
		
		public int getAngleThisTurn(int turnNum) {
			if (turnNum == lastThrustUpdate) return angle;
			return 0;
		}
	}
}
