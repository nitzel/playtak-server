/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package tak;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.mindrot.jbcrypt.BCrypt;
import tak.utils.ConcurrentHashSet;
import java.util.concurrent.locks.*;

/**
 *
 * @author chaitu
 */
public class Player {
	public static Lock loginLock=new ReentrantLock();
	public static Map<String, Player> players = new ConcurrentHashMap<>();
	public static Map<String, Player> guestsByToken = new ConcurrentHashMap<>();
	public static ConcurrentHashSet<Player> modList = new ConcurrentHashSet<>();
	public static ConcurrentHashSet<Player> gagList = new ConcurrentHashSet<>();
	public static ConcurrentHashSet<String> takenName = new ConcurrentHashSet<>();
	
	static int idCount=0;
	static AtomicInteger guestCount = new AtomicInteger(0);
	
	private String name;
	private String password;
	private String email;
	private int id;//Primary key

	//Ratings for 4x4.. 8x8 games
	private int r4;
	private int r5;
	private int r6;
	private int r7;
	private int r8;
	
	private boolean guest;
	public boolean isbot=false;
	public boolean is_admin = false;
	public boolean is_mod = false;
	private boolean gag = false;//don't broadcast his shouts
	//variables not in database
	public Client client;
	private Game game;
	public long lastActivity;
	public static long lastCleanup=System.nanoTime();
	
	private String resetToken;
	
	static void cleanUpGuests(){
		long now=System.nanoTime();
		if(now-lastCleanup<3600000000000L){
			return;
		}
		lastCleanup=now;
		loginLock.lock();
		try{
			guestsByToken.forEach( (k, v) -> {
				if(!v.isLoggedIn() && now-v.lastActivity>20000000000000L){
					guestsByToken.remove(k);
					players.remove(v.name);
				}
			});
		}
		finally{
			loginLock.unlock();
		}
	}
	
	Player(String name, String email, String password, int id, int r4, int r5,
			int r6, int r7, int r8, boolean guest, boolean bot, boolean admin, boolean mod) {
		this.name = name;
		this.email = email;
		this.password = password;
		this.id = id;
		this.r4 = r4;
		this.r5 = r5;
		this.r6 = r6;
		this.r7 = r7;
		this.r8 = r8;
		this.guest = guest;
		this.resetToken = "";
		this.isbot=bot;
		this.is_admin = admin;
		this.is_mod = mod;

		if(mod){
			setMod();
		}
		
		client = null;
		game = null;
		this.lastActivity=System.nanoTime();
	}
	
	public static String uniqifyName(String name){
		name=name.toLowerCase();
		name=name.replaceAll("[^0-9a-z]","");
		name=name.replaceAll("[il]","1");
		name=name.replaceAll("[o]","0");
		return name;
	}
	
	public static void takeName(String name){
		takenName.add(uniqifyName(name));
	}
	
	public static boolean isNameTaken(String name){
		return takenName.contains(uniqifyName(name));
	}
	
	public static String hash(String st) {
		return BCrypt.hashpw(st, BCrypt.gensalt());
	}
	
	public boolean authenticate(String candidate) {
		if(guest){
			return false;
		}
		else{
			return BCrypt.checkpw(candidate, password);
		}
	}
	
	public void sendResetToken() {
		resetToken = new BigInteger(130, random).toString(32);
		EMail.send(this.email, "playtak.com reset password", "Your reset token is " + resetToken);
	}
	
	public boolean resetPassword(String token, String newPass) {
		if((!"".equals(resetToken)) && token.equals(resetToken)) {
			setPassword(newPass);
			resetToken = "";
			return true;
		}
		return false;
	}
	
	public boolean isLoggedIn() {
		return client!=null;
	}
	
	public Game getGame() {
		return game;
	}
	
	public void setGame(Game g) {
		game = g;
	}
	
	public void removeGame() {
		game = null;
	}
	
	public void send(String msg) {
		if(client != null)
			client.send(msg);
	}
	
	public void sendNOK() {
		if(client != null)
			client.sendNOK();
	}
	
	public void sendWithoutLogging(String msg) {
		if(client != null)
			client.sendWithoutLogging(msg);
	}
	
	public void login(Client c) {
		this.client = c;
		resetToken = "";//If a user is able to login, he has the password
		
		if(game != null)
			game.playerRejoin(this);
	}
	
	public void logout() {
		if(client != null) {
			client.disconnect();
			this.client = null;
		}
	}
	
	public boolean isMod() {
		return is_mod;
	}
	
	public void setMod() {
		is_mod = true;
		modList.add(this);
	}
	
	public void unMod() {
		is_mod = false;
		modList.remove(this);
	}

	public boolean isAdmin() {
		return is_admin;
	}
	
	public void gag() {
		gag = true;
		Player.gagList.add(this);
	}
	
	public void unGag() {
		gag = false;
		Player.gagList.remove(this);
	}
	
	public boolean isGagged() {
		return gag;
	}
	
	public void loggedOut() {
		this.client = null;
		if(guest) {
			Player.modList.remove(this);
			Player.gagList.remove(this);
		}
	}
	
	Player(String name, String email, String password, boolean guest) {
		this(name, email, password, guest?0:++idCount, 0, 0, 0, 0, 0, guest, false, false, false);
	}
	
	Player() {
		this("Guest"+guestCount.incrementAndGet(), "", "", true);
		Player.players.put(this.name, this);
		guestsByToken.put(this.name, this);
	}
	
	Player(String token) {
		this("Guest"+guestCount.incrementAndGet(), "", token, true);
		Player.players.put(this.name, this);
		guestsByToken.put(token, this);
	}
	
	Client getClient() {
		return client;
	}
	
	static SecureRandom random = new SecureRandom();
	public static Player createPlayer(String name, String email) {
		BigInteger pwsource = new BigInteger(95, random);
		BigInteger n26 = new BigInteger("26");
		String tmpPass = "";
		int a;
		for(a=0;a<5;a++){
			tmpPass+=pwsource.mod(n26).add(BigInteger.TEN).toString(36);
			pwsource=pwsource.divide(n26);
			tmpPass+=pwsource.mod(n26).add(BigInteger.TEN).toString(36);
			pwsource=pwsource.divide(n26);
			tmpPass+=pwsource.mod(BigInteger.TEN).toString();
			pwsource=pwsource.divide(BigInteger.TEN);
			tmpPass+=pwsource.mod(BigInteger.TEN).toString();
			pwsource=pwsource.divide(BigInteger.TEN);
		}
		
		Player np = new Player(name, email, Player.hash(tmpPass), false);
		String sql = "INSERT INTO players (id,name,password,email,r4,r5,r6,r7,r8) "+
							"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
		
		try {
			PreparedStatement stmt = Database.playersConnection.prepareStatement(sql);
			stmt.setInt(1, np.id);
			stmt.setString(2, np.name);
			stmt.setString(3, np.password);
			stmt.setString(4, np.email);
			stmt.setInt(5, np.r4);
			stmt.setInt(6, np.r5);
			stmt.setInt(7, np.r6);
			stmt.setInt(8, np.r7);
			stmt.setInt(9, np.r8);

			stmt.executeUpdate();
			stmt.close();
			
			EMail.send(np.email, "playtak.com password", "Hello "+np.name+", your password is "+tmpPass+" You can change it on playtak.com.");
			players.put(np.name, np);
			takeName(np.name);
		} catch (SQLException ex) {
			Logger.getLogger(Player.class.getName()).log(Level.SEVERE, null, ex);
		}
		return np;
	}
	
	@Override
	public String toString() {
		return name+" "+password+" "+email+" "+r4+" "+r5+" "+r6+" "+r7+" "+r8;
	}
	
	public int getRating(long time){
		String sql="SELECT rating, ratingage, ratingbase, unrated FROM players WHERE id=?";
		double decayrate=1000.0*60.0*60.0*24.0*240.0;
		double rating=0.0;
		double ratingage=0.0;
		int ratingbase=0;
		int unrated=1;
		ResultSet rs=null;
		try{
			try( 
				PreparedStatement stmt = Database.playersConnection.prepareStatement(sql);
			){
				stmt.setInt(1, id);
				rs = stmt.executeQuery();
				if (rs.next()) {
					rating=rs.getDouble("rating");
					ratingage=rs.getDouble("ratingage");
					ratingbase=rs.getInt("ratingbase");
					unrated=rs.getInt("unrated");
				}
			} catch (SQLException ex) {
				Logger.getLogger(Player.class.getName()).log(Level.SEVERE, null, ex);
			}
			finally{
				if(rs!=null){
					rs.close();
				}
			}

			if(ratingbase!=0){
				try( 
					PreparedStatement stmt=Database.playersConnection.prepareStatement(sql);
				){
					stmt.setInt(1, ratingbase);
					rs = stmt.executeQuery();
					if (rs.next()) {
						rating=rs.getDouble("rating");
						ratingage=rs.getDouble("ratingage");
						ratingbase=rs.getInt("ratingbase");
						unrated=rs.getInt("unrated");
					}
				} catch (SQLException ex) {
					Logger.getLogger(Player.class.getName()).log(Level.SEVERE, null, ex);
				}
				finally{
					if(rs!=null){
						rs.close();
					}
				}
			}
		}catch (SQLException ex) {
			Logger.getLogger(Player.class.getName()).log(Level.SEVERE, null, ex);
		}
		if(unrated==1){
			return 0;
		}
		if(rating<1500.0){
			return (int)rating;
		}
		double decaytime=((double)time-ratingage)/decayrate;

		if(decaytime<1.0){
			return (int)rating;
		}
		double retention=Math.pow(0.5,decaytime)*2.0;
		if(rating<1700.0){
			return (int)Math.min(rating,1500.0+retention*200.0);
		}
		else{
			return (int)(rating-200.0*(1.0-retention));
		}
	}
	
	public void setR4(int r4) {
		this.r4 = r4;
		String sql = "UPDATE players set r4 = ? where id = ?;";
		
		try {
			PreparedStatement stmt = Database.playersConnection.prepareStatement(sql);
			stmt.setInt(1, r4);
			stmt.setInt(2, id);
			stmt.executeUpdate();
			stmt.close();
		} catch (SQLException ex) {
			Logger.getLogger(Player.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
	public void setR5(int r5) {
		this.r5 = r5;
		String sql = "UPDATE players set r5 = ? where id = ?;";
		
		try {
			PreparedStatement stmt = Database.playersConnection.prepareStatement(sql);
			stmt.setInt(1, r5);
			stmt.setInt(2, id);
			stmt.executeUpdate();
			stmt.close();
		} catch (SQLException ex) {
			Logger.getLogger(Player.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
	public void setR6(int r6) {
		this.r6 = r6;
		String sql = "UPDATE players set r6 = ? where id = ?;";
		
		try {
			PreparedStatement stmt = Database.playersConnection.prepareStatement(sql);
			stmt.setInt(1, r6);
			stmt.setInt(2, id);
			stmt.executeUpdate();
			stmt.close();
		} catch (SQLException ex) {
			Logger.getLogger(Player.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
	public void setR7(int r7) {
		this.r7 = r7;
		String sql = "UPDATE players set r7 = ? where id = ?;";
		
		try {
			PreparedStatement stmt = Database.playersConnection.prepareStatement(sql);
			stmt.setInt(1, r7);
			stmt.setInt(2, id);
			stmt.executeUpdate();
			stmt.close();
		} catch (SQLException ex) {
			Logger.getLogger(Player.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
	public void setR8(int r8) {
		this.r8 = r8;
		String sql = "UPDATE players set r8 = ? where id = ?;";
		
		try {
			PreparedStatement stmt = Database.playersConnection.prepareStatement(sql);
			stmt.setInt(1, r8);
			stmt.setInt(2, id);
			stmt.executeUpdate();
			stmt.close();
		} catch (SQLException ex) {
			Logger.getLogger(Player.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
	
	public void setPassword(String pass) {
		this.password = Player.hash(pass);
		
		String sql = "UPDATE players set password = ? where id = ?;";
		
		try {
			PreparedStatement stmt = Database.playersConnection.prepareStatement(sql);
			stmt.setString(1, this.password);
			stmt.setInt(2, id);
			stmt.executeUpdate();
			stmt.close();
		} catch (SQLException ex) {
			Logger.getLogger(Player.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	public void setModInDB(String name, int mod) {
		String sql = "UPDATE players set is_mod = ? where name = ?;";

		try {
			PreparedStatement stmt = Database.playersConnection.prepareStatement(sql);
			stmt.setInt(1, mod);
			stmt.setString(2, name);
			stmt.executeUpdate();
			stmt.close();
		} catch (SQLException ex) {
			Logger.getLogger(Player.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	public int getR4() {
		return r4;
	}
	
	public int getR5() {
		return r5;
	}
	
	public int getR6() {
		return r6;
	}
	
	public int getR7() {
		return r7;
	}
	
	public int getR8() {
		return r8;
	}
	
	public String getName() {
		return name;
	}
	
	public String getEmail() {
		return email;
	}
	
	public String getPassword() {
		return password;
	}
	
	public int getId() {
		return id;
	}
	
	public static void loadFromDB() {
		idCount=0;
		try (Statement stmt = Database.playersConnection.createStatement();
				ResultSet rs = stmt.executeQuery("SELECT * FROM players;")) {
			while(rs.next()) {
				Player np = new Player(
							rs.getString("name"),
							rs.getString("email"),
							rs.getString("password"),
							rs.getInt("id"),
							rs.getInt("r4"),
							rs.getInt("r5"),
							rs.getInt("r6"),
							rs.getInt("r7"),
							rs.getInt("r8"),
							false,
							rs.getInt("isbot") == 1,
							rs.getInt("is_admin") == 1,
							rs.getInt("is_mod") == 1
						);
				players.put(np.name, np);
				takeName(np.name);
				if(idCount<np.id)
					idCount=np.id;
				
			}
		} catch (SQLException ex) {
			Logger.getLogger(Player.class.getName()).log(Level.SEVERE, null, ex);
		}
	}
}
