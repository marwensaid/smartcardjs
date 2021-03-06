/*
 * SCUBA smart card framework.
 *
 * Copyright (C) 2009  The SCUBA team.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * $Id: $
 */

package net.sourceforge.scuba.smartcards;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Properties;

import net.sourceforge.scuba.util.Hex;

/**
 * Fingerprint data structure. Basically maps commands to response codes (status words).
 * <strong>Under construction!</strong> Reads and parses files in <code>basedir/fingerprintfiles</code>
 * formatted with filestructures of Henning.
 * 
 * TODO: We should think of some other fileformat: Henning's format is based on
 * <code>toString()</code> of <code>javax.smartcardio.*APDU</code> classes,
 * not under our control.
 * 
 * TODO: For long term: maybe turn this into some CardType-like system similar to OCF?
 *
 * @author Henning Richter (hrichter@fh-lausitz.de)
 * @author Martijn Oostdijk (martijn.oostdijk@gmail.com)
 */
public class APDUFingerprint<C,R> implements CardFingerprint
{
	/** The fingerprint directory contains txt and png files. We only consider the txt ones. */
	private static final FilenameFilter HENNING_FILE_FILENAME_FILTER = new FilenameFilter() {
		public boolean accept(File dir, String name) {
			return name.endsWith("txt") || name.endsWith("TXT");
		}			
	};

	
	/**
	 * The set of commands to send to card to create a fingerprint.
	 * TODO: Perhaps make this a parameter to the constructor? -- MO
	 */
	private final Collection<C> FINGERPRINT_COMMANDS = new LinkedList<C>();
	private void prepareFingerpringCommands() {
		ScubaSmartcards<C, R> sc = ScubaSmartcards.getInstance();
		FINGERPRINT_COMMANDS.add( sc.createCommandAPDU((byte)0x00, (byte)0x44, (byte)0x00, (byte)0x00, new byte[0], 0x00) );
		FINGERPRINT_COMMANDS.add( sc.createCommandAPDU((byte)0x00, (byte)0x82, (byte)0x00, (byte)0x00, new byte[0], 0x00) );
		FINGERPRINT_COMMANDS.add( sc.createCommandAPDU( (byte)0x00, (byte)0x84, (byte)0x00, (byte)0x00, new byte[0], 0x00) );
		FINGERPRINT_COMMANDS.add( sc.createCommandAPDU( (byte)0x00, (byte)0x88, (byte)0x00, (byte)0x00, new byte[0], 0x00) );
		FINGERPRINT_COMMANDS.add( sc.createCommandAPDU( (byte)0x00, (byte)0xB0, (byte)0x00, (byte)0x00, new byte[0], 0x00) );
		FINGERPRINT_COMMANDS.add( sc.createCommandAPDU( (byte)0x00, (byte)0xA4, (byte)0x00, (byte)0x00, new byte[0], 0x00) );
		FINGERPRINT_COMMANDS.add( sc.createCommandAPDU( (byte)0x00, (byte)0xB1, (byte)0x00, (byte)0x00, new byte[0], 0x00) );
	}

	/** Fingerprints to compare with are read from file during static init. */
	private static final Map<APDUFingerprint, String> STORED_FINGERPRINTS = new HashMap<APDUFingerprint, String>();
//	static {
//		getFingerprintsFromHenningFile();
//	};

	/** Maps commands (headers) to responses (status words). */
	private Map<C, Short> commandResponsePairs;

	/**
	 * Constructs an empty fingerprint.
	 */
	public APDUFingerprint() {
		commandResponsePairs = new HashMap<C, Short>();
	}

	/**
	 * Constructs a fingerprint by sending some APDUs to the card connected to <code>service</code>.
	 * FIXME: only tested with PassportService, which selects the passportapplet when opened!
	 *
	 * @param service some card service
	 */
	public APDUFingerprint(CardService<C,R> service) {
		this();
		prepareFingerpringCommands();
		try {
			if (service.isOpen()) { service.close(); }
			service.open();
			for (C capdu: FINGERPRINT_COMMANDS) {
				R rapdu = service.transmit(capdu);
				
				ScubaSmartcards<C, R> sc = ScubaSmartcards.getInstance();
					
				short sw = (short)(sc.accesR(rapdu).getSW());
				if (sw != ISO7816.SW_NO_ERROR) {
					put(capdu, sw);
				}
			}
		} catch (CardServiceException cse) {
			cse.printStackTrace();
		}
	}

	/**
	 * Adds a command-response pair to this fingerprint.
	 *
	 * @param capdu command
	 * @param rapdu response
	 */
	public void put(C capdu, R rapdu) {
		ScubaSmartcards<C, R> sc = ScubaSmartcards.getInstance();
		put(capdu, (short)(sc.accesR(rapdu).getSW() & 0xFFFF));
	}
	
	/**
	 * Adds a command-response pair to this fingerprint.
	 *
	 * @param capdu command apdu
	 * @param sw response
	 */
	public void put(C capdu, short sw) {
		commandResponsePairs.put(capdu, sw);
	}

	/**
	 * Produces some string that identifies this card.
	 *
	 * @return some string that identifies this card
	 */
	public Properties guessProperties() {
		Properties properties = new Properties();
		int i = 0;
		for (APDUFingerprint storedFingerprint: STORED_FINGERPRINTS.keySet()) {
			if (isAllowedBy(this, storedFingerprint)) {
				String fileName = STORED_FINGERPRINTS.get(storedFingerprint);
				properties.put("stored.fingerprint.match." + i, fileName);
			}
		}
		return properties;
	}

//	private static void getFingerprintsFromHenningFile() {
//		/* TODO: the fileformat needs some cleaning up!
//		 * Make filenames follow ISO country codes? (Or use field within file to define country)
//		 */
//		try {
//			File baseDir = Files.getBaseDirAsFile();
//			File fingerprintDir = new File(baseDir, "fingerprintfiles");
//			File[] fingerPrintFiles = fingerprintDir.listFiles(HENNING_FILE_FILENAME_FILTER);
//			if (fingerPrintFiles == null) {
//				System.out.println("DEBUG: cannot open fingerprint dir: " + fingerprintDir);
//				return;
//			}
//			for (File fingerPrintFile: fingerPrintFiles) {
//				String fileName = fingerPrintFile.getName();
//				try {
//					APDUFingerprint fingerprint = getFingerprintFromHenningFile(fingerPrintFile);
//					STORED_FINGERPRINTS.put(fingerprint, fileName);
//				} catch (Exception e) {
//					System.out.println("WARNING: ignoring Henning-file \"" + fileName + "\"");
//					continue; /* Try next file. */
//				}
//			}
//		} catch (Exception e) {
//			e.printStackTrace();
//		}
//	}
	
	/**
	 * Gets a fingerprint from a Henning-file.
	 * 
	 * @param file a henning file
	 * @return a fingerprint
	 */
//	private static APDUFingerprint getFingerprintFromHenningFile(File file) {
//		APDUFingerprint result = new APDUFingerprint();
//		try {
//			byte[] fileContentBytes = new byte[(int)file.length()];
//			DataInputStream dataIn = new DataInputStream(new FileInputStream(file));
//			dataIn.readFully(fileContentBytes);
//			dataIn.close();
//			String fileContents = new String(fileContentBytes, "UTF-8");			
//			StringTokenizer st = new StringTokenizer(fileContents, "\n");
//			int cla = -1, ins = -1, p1 = -1, p2 = -1, nc = -1, ne = -1;
//			int sw = -1;
//			while (st.hasMoreTokens()) {
//				String line = st.nextToken().trim().toLowerCase();
//				if (line.indexOf("instruction:") >= 0) {
//					cla = -1;
//					ins = getByteValue("instruction:", line);
//					p1 = getByteValue("p1:", line);
//					p2 = getByteValue("p2:", line);
//					nc = -1;
//					ne = -1;
//					sw = -1;
//				} else if (line.indexOf("c:") >= 0) {
//					ins = getByteValue("c:", line);
//					nc = getByteValue("nc=", line);
//					ne = getByteValue("ne=", line);
//					sw = getShortValue("r:", line);
//					C pair = new CommandAPDU(cla, ins, p1, p2, new byte[nc], ne);
//					result.commandResponsePairs.put(pair, (short)(sw & 0xFFFF));
//				} else {
//					/* Skip this line. */
//					cla = -1;
//					ins = -1;
//					p1 = -1;
//					p2 = -1;
//					continue;
//				}
//			}
//		} catch (IOException ioe) {
//			throw new IllegalArgumentException("Cannot read from file " + file);
//		}
//		return result;
//	}

	private static int getByteValue(String key, String line) {
		try {
			String value = getValue(key, line);
			return Integer.parseInt(value, 16) & 0xFF;
		} catch (NumberFormatException nfe) {
			return -1; 
		}
	}

	private static int getShortValue(String key, String line) {
		try {
			return Integer.parseInt(getValue(key, line), 16) & 0xFFFF;
		} catch (NumberFormatException nfe) {
			return -1;
		}
	}

	/**
	 * Searches for key in Henning-file lines, returns hex value of next token.
	 *
	 * @param key
	 * @param line
	 * @return value of key
	 */
	private static String getValue(String key, String line) {
		line = line.trim();
		line = line.substring(line.indexOf(key) + key.length());
		line = line.trim();
		StringBuffer value = new StringBuffer();
		for (int i = 0; i < line.length() && "0123456789abcdef".indexOf(line.charAt(i)) >= 0; i++) {
			value.append(line.charAt(i));
		}
		return value.toString();
	}

	private int getResponse(C capdu) {
		Short r = commandResponsePairs.get(capdu);
		if (r == null) {
			return -1;
		}
		return (r.shortValue() & 0xFFFF);
	}

	/**
	 * 
	 * @param otherPrint pattern
	 * @return
	 */
	private boolean isAllowedBy(APDUFingerprint<C,R> thisPrint, APDUFingerprint otherPrint) {
		for (C c: thisPrint.commandResponsePairs.keySet()) {
			C otherC = getSimilarCommandAPDU(c, otherPrint);
			if (otherC == null) { continue; }
			if (!isAllowedBy(c, otherC)) { return false; }
			int response = thisPrint.getResponse(c);
			int otherResponse = otherPrint.getResponse(otherC);
			if (response == -1 || otherResponse == -1) { continue; }
			if (response != otherResponse) { return false; }
		}
		System.out.println("DEBUG: isAllowedBy(" + thisPrint + ", " + otherPrint + ")");
		return true;
	}

	private C getSimilarCommandAPDU(C capdu, APDUFingerprint<C,R> print) {
		ScubaSmartcards<C, R> sc = ScubaSmartcards.getInstance();
		for (C c: print.commandResponsePairs.keySet()) {
			if ( sc.accesC(capdu).getINS() == sc.accesC(c).getINS()) { return c; }
		}
		return null;
	}

	private boolean isAllowedBy(C capdu, C otherCapdu) {
		ScubaSmartcards<C, R> sc = ScubaSmartcards.getInstance();
		
		return isAllowedBy( sc.accesC(capdu).getINS(), sc.accesC(otherCapdu).getINS());
	}

	private static boolean isAllowedBy(int a, int b) {
		return b == -1 || a == b;
	}

	public boolean equals(Object obj) {
		if (obj == null) { return false; }
		if (obj == this) { return true; }
		if (obj.getClass() != this.getClass()) { return false; }
		APDUFingerprint other = (APDUFingerprint)obj;
		return commandResponsePairs.equals(other.commandResponsePairs);
	}
	
	public int hashCode() {
		return 3 * commandResponsePairs.hashCode() + 11;
	}

	public String toString() {
		StringBuffer result = new StringBuffer();
		int i = 0, n = commandResponsePairs.size();
		result.append("[");
		for (C c: commandResponsePairs.keySet()) {
			short sw = commandResponsePairs.get(c);
			ScubaSmartcards<C, R> sc = ScubaSmartcards.getInstance();
			
			result.append(Hex.bytesToHexString( sc.accesC(c).getBytes()) + " -> " + Hex.shortToHexString((short)(sw & 0xFFFF)));
			i++;
			if (i < n) { result.append(", "); }
		}
		result.append("]");
		return result.toString();
	}
}
