package com.ibm.ta.modresorts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DefaultWeatherData {
	
	final static Logger logger = LogManager.getLogger(DefaultWeatherData.class);
	
	private String city = null;
	
	public DefaultWeatherData(String city) {
		if (city == null) {
			logger.error("fail initializing DefaultWeatherData because the given city value is null");
			throw new UnsupportedOperationException("City is not defined");
		}
		boolean isSupportedCity = false;
		
		for (String aSupportedCity : Constants.SUPPORTED_CITIES) {
			if (city.equals(aSupportedCity)) {
				isSupportedCity = true;
			}
		}
		if (isSupportedCity) {
			this.city = city;
		} else {
			logger.error("fail initializing DefaultWeatherData because the given city " + city + " is not supported");
			throw new UnsupportedOperationException("City is invalid. It must be one of " + Constants.SUPPORTED_CITIES.toString());
		}
	}
	
	public String getDefaultWeatherData () throws IOException {		
		
		String dataFileName = null;
		if (Constants.PARIS.equals(city)) {
			dataFileName = Constants.PARIS_WEATHER_FILE;
		} else if (Constants.LAS_VEGAS.equals(city)) {
			dataFileName = Constants.LAS_VEGAS_WEATHER_FILE;
		} else if (Constants.SAN_FRANCISCO.equals(city)) {
			dataFileName = Constants.SAN_FRANCESCO_WEATHER_FILE;
		} else if (Constants.MIAMI.equals(city)) {
			dataFileName = Constants.MIAMI_WEATHER_FILE;
		} else if (Constants.CORK.equals(city)) {
			dataFileName = Constants.CORK_WEATHER_FILE;
		} else if (Constants.BARCELONA.equals(city)) {
			dataFileName = Constants.BACELONA_WEATHER_FILE;
		}else {
			throw new UnsupportedOperationException("The default weather information for the selected city: " + city + 
					" is not provided.  Valid selections are: " + Constants.SUPPORTED_CITIES);
		}
		logger.debug("dataFileName: " + dataFileName);
		
		InputStream inputStream = null;
		
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			inputStream = getClass().getClassLoader().getResourceAsStream(dataFileName);
			byte[] buf = new byte[4096];
			for (int n; 0 < (n = inputStream.read(buf));) {
				out.write(buf, 0, n);
			}
		} finally {
			out.close();
			
			if (inputStream != null) {
				inputStream.close();
			}
			inputStream = null;
		}
				
	    String resultStr = new String(out.toByteArray(), "UTF-8");
	    logger.debug("resultStr: " + resultStr);
	    out = null;
	    return resultStr;
		
	}

}