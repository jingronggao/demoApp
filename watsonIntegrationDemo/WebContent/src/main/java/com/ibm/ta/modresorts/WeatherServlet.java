package com.ibm.ta.modresorts;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class WeatherServlet extends HttpServlet {

	// local OS environment variable key name.  The key value should provide an API key that will be used to
	// get weather information from site: http://www.wunderground.com
	private static final String WEATHER_API_KEY = "WEATHER_API_KEY";  
	
	static final long serialVersionUID = 1L;
	
	final static Logger logger = LogManager.getLogger(WeatherServlet.class);

	/**
	 * constructor
	 */
	public WeatherServlet() {
		super();
	}

	/**
	 * Returns the weather information for a given city
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException{
		logger.debug("WeatherServlet doGet() called");
		
		String city = request.getParameter("selectedCity");
		logger.debug("requested city is " + city);
		
		String weatherAPIKey = System.getenv(WEATHER_API_KEY);
		logger.debug("weatherAPIKey is " + weatherAPIKey);
		
		if (weatherAPIKey != null && weatherAPIKey.trim().length() > 0) {
			logger.info("weatherAPIKey is found: " + weatherAPIKey + ", system will provide the real time weather data for the city " + city);
			getRealTimeWeatherData(city, weatherAPIKey, response);
		}else {
			logger.info("weatherAPIKey is not found, will provide the weather data dated August 10th, 2018 for the city " + city);
			getDefaultWeatherData(city, response);
		}
	}
	
	private void getRealTimeWeatherData(String city, String apiKey, HttpServletResponse response) 
			throws ServletException, IOException {
		String resturl = null;

		if (Constants.PARIS.equals(city)) {
			resturl = "http://api.wunderground.com/api/" + apiKey + "/forecast/geolookup/conditions/q/France/Paris.json";
		} else if (Constants.LAS_VEGAS.equals(city)) {
			resturl = "http://api.wunderground.com/api/" + apiKey + "/forecast/geolookup/conditions/q/NV/Las_Vegas.json";
		} else if (Constants.SAN_FRANCISCO.equals(city)) {
			resturl = "http://api.wunderground.com/api/" + apiKey + "/forecast/geolookup/conditions/q/CA/San_Francisco.json";
		} else if (Constants.MIAMI.equals(city)) {
			resturl = "http://api.wunderground.com/api/" + apiKey + "/forecast/geolookup/conditions/q/FL/Miami.json";
		} else if (Constants.CORK.equals(city)) {
			resturl = "http://api.wunderground.com/api/" + apiKey + "/forecast/geolookup/conditions/q/ireland/cork.json";
		} else if (Constants.BARCELONA.equals(city)) {
			resturl = "http://api.wunderground.com/api/" + apiKey + "/forecast/geolookup/conditions/q/Spain/Barcelona.json";
		}else {			
			String errorMsg = "Sorry, the weather information for your selected city: " + city + 
					" is not available.  Valid selections are: " + Constants.SUPPORTED_CITIES;
			handleException(null, errorMsg);
		}
		
		logger.debug("Weather REST url for city " + city + " is: " + resturl);
			
		URL obj = null;
		HttpURLConnection con = null;
		try {
			obj = new URL(resturl);
			con = (HttpURLConnection) obj.openConnection();
			con.setRequestMethod("GET");
		} catch (MalformedURLException e1) {
			String errorMsg = "Caught MalformedURLException. Please make sure the url is correct.";
			handleException(e1, errorMsg);
		}catch (ProtocolException e2) {
			String errorMsg = "Caught ProtocolException: " + e2.getMessage() + ". Not able to set request method to http connection.";
			handleException(e2, errorMsg);
		} catch (IOException e3) {
			String errorMsg = "Caught IOException: " + e3.getMessage() + ". Not able to open connection.";
			handleException(e3, errorMsg);
		} 
		
		int responseCode = con.getResponseCode();
		logger.debug("Response Code: " + responseCode);		
		
		if (responseCode >= 200 && responseCode < 300) {

			BufferedReader in = null;
			ServletOutputStream out = null;

			try {
				in = new BufferedReader(new InputStreamReader(con.getInputStream()));
				String inputLine = null;
				StringBuffer responseStr = new StringBuffer();
				
				while ((inputLine = in.readLine()) != null) {
					responseStr.append(inputLine);
				}
	

				response.setContentType("application/json");
				out = response.getOutputStream();
				out.print(responseStr.toString());
				logger.debug("responseStr: " + responseStr);
			} catch (Exception e) {
				String errorMsg = "Problem occured when processing the weather server response.";
				handleException(e, errorMsg);
			} finally {
				if (in != null) {
					in.close();
				}
				if (out!= null) {
					out.close();
				}
				in = null;
				out = null;
			}
		} else {
			String errorMsg = "REST API call " + resturl + " returns an error response: " + responseCode;
			handleException(null, errorMsg);
		}
	}
	
	private void getDefaultWeatherData(String city, HttpServletResponse response) 
			throws ServletException, IOException {
		DefaultWeatherData defaultWeatherData = null;
	
		try {
			defaultWeatherData = new DefaultWeatherData(city);
		}catch (UnsupportedOperationException e) {
			handleException(e, e.getMessage());
		}
		
		ServletOutputStream out = null;
		
		try {
			String responseStr = defaultWeatherData.getDefaultWeatherData();
			response.setContentType("application/json");
			out = response.getOutputStream();
			out.print(responseStr.toString());
			logger.debug("responseStr: " + responseStr);
		} catch (Exception e) {
				String errorMsg = "Problem occured when getting the default weather data.";
				handleException(e, errorMsg);
		} finally {
				
			if (out!= null) {
				out.close();
			}
				
			out = null;
		}
	}

	private void handleException(Exception e, String errorMsg) throws ServletException {
		if (e == null) {
			logger.error(errorMsg);
			throw new ServletException(errorMsg);
		}else {
			logger.error(errorMsg, e);
			throw new ServletException(errorMsg, e);
		}
	}

	/**
	 * Returns the weather information for a given city
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		doGet(request, response);
	}	
	
}