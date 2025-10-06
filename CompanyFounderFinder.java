import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List; 
import java.util.HashMap; 
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.FileReader;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * COMPANY FOUNDER FINDER:
 *  1. Reads company names and URL's from a text file that is given
 *  2. Using Google's Custom Search API to find relevant pages within the company's website for each company 
 *  3. Gets those HTML pages and parses them
 *  4. Using OpenAI's GPT (model 4.1-mini though any model should give similar results) to extract the founder names for each company
 *  5. Exports the results to a JSON file
*/
public class CompanyFounderFinder {

    // API KEYS
    private static final String API_KEY = "API_KEY_HERE"; 
    private static final String SEARCH_ENGINE_ID =  "SEARCH_ENGINE_ID HERE";
    private static final String GOOGLE_API_URL = "API_URL_HERE";
    private static final String OPEN_AI_KEY = "API_KEY_HERE";
    
    /*
     * Main Method 
     * Reads company data from each file and processes each line
     * Searches for the founders within each company
     * Stores the results in a HashMap 
     * Exports the results
     */
    public static void main(String[] args) {

        // map to store results: company name -> list of founder names
        Map<String, List<String>> results = new HashMap<>(); 
        String filePath = "/Users/shivanigodse/Desktop/companies.txt";

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line; 
            while ((line = br.readLine()) != null) {
                // DEBUG: System.out.println(line); 

                CompanyData company = parseCompanyData(line); 

                // DEBUG: System.out.println("Name:" + company.name); 
                // DEBUG: System.out.println("URL: " + company.url);

                List<String> founders = searchForFounders(company.url, company.name);

                // DEBUG: System.out.println("Founders found: " + founders); 
                // DEBUG: System.out.println("----------------------\n");

                // store the results 
                results.put(company.name, founders);
            }

            // export results to JSON file
            writeResultsToJSON(results, "/Users/shivanigodse/Desktop/founders.json"); 
            System.out.println("\n Results have been saved to a json"); 
        } catch (IOException e){
            System.err.println("Error reading file: " + e.getMessage());
            e.printStackTrace();

        }

    }
    /*
     * Parses a line from the .txt file in order to grab the name and the company URL
     * Expects the same format as that given in the test file
     * paramaters: the line to parse
     * returns: a CompanyData object that stores the name and URL seperately 
     */
    private static CompanyData parseCompanyData(String line) {
        String[] parts = line.split("\\(");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid line format: " + line);
        }

        // extract company name
        String name = parts[0].trim(); 

        // extract URL
        String url = parts[1].replace(")", "").trim(); 
        return new CompanyData(name, url); 
    }

    /* 
     * Searches for founders of the company using Google's Custom Search API 
     * How it works: 
     * 1. Extracts domain from company URL
     * 2. Searches for pages containing the "founders" or "cofounders"
     * 3. Parses the HTML content from the leading results 
     * 4. Uses OpenAI to actually extract the names and return them
     *
     * parameter: companyName - the company which to search for
     * return: returns the lit of founders if found (empty list if cannot find)
     */
    private static List<String> searchForFounders(String companyUrl, String companyName) {
        List<String> founders = new ArrayList<>(); 
        try {
            // extracting the domain names
            URL compUrl = new URL(companyUrl); 
            String host = compUrl.getHost(); 
            
            // removes the "www." prefix if there
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }

            // builds the Custom Google Search query 
            // capital OR gives more reliable results through custom search API 
            String searchQuery = "founders OR cofounders";
            String encodedQuery = java.net.URLEncoder.encode(searchQuery, "UTF-8"); 
    
            String apiUrl = GOOGLE_API_URL + 
                            "?key=" + API_KEY + 
                            "&cx=" + SEARCH_ENGINE_ID + 
                            "&q=" + encodedQuery +
                            "&siteSearch=" + host;  // restricts the search to JUST the company's domain
            
            
            // DEBUG: System.out.println("Searching... " + apiUrl);


            // send request to API 
            HttpClient client = HttpClient.newHttpClient(); 
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiUrl)).GET().build(); 
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // DEBUG: System.out.println("API successfully called");
                JSONObject jsonResponse = new JSONObject(response.body());
                // DEBUG: System.out.println(response.body());
                
                // check to see if the search returned any results
                if (jsonResponse.has("items")) {
                    JSONArray items = jsonResponse.getJSONArray("items");
                    // DEBUG: System.out.println("Found" + items.length() + "search results");

                    // process the top 5 search results
                    int urlsToProcess = Math.min(5, items.length()); 

                    for (int i = 0; i < urlsToProcess; i++) {
                        JSONObject item = items.getJSONObject(i); 

                        String url = item.optString("link", ""); 

                        if (!url.isEmpty()) {
                            // DEBUG: System.out.println("\nProcessing URL " + (i+1) + ": " + url);

                            // get the content of the HTML from the page
                            String bodyContent = fetchBodyContent(url);

                            if (!bodyContent.isEmpty()) {
                            
                                // split into chunks that stay within OpenAI's token limits
                                List<String> chunks = splitIntoChunks(bodyContent, 32*1024); 
                                // DEBUG: System.out.println("Split into " + chunks.size() + "chunks"); 

                                // process each chunk 
                                for (int j = 0; j < chunks.size(); j++) {
                                    // DEBUG: System.out.println("Processing chunk: " + (j + 1)); 
                                    List<String> foundInChunk = getFounderNames(chunks.get(j), companyName); 

                                    // add new founders (deduplicate)
                                    for (String founder : foundInChunk) {
                                        if (!founders.contains(founder)) {
                                            founders.add(founder);
                                        }
                                    }

                                    // delays each call by 2000 milliseconds to avoid rate limiting 
                                    if (j < chunks.size() - 1) {
                                        try {
                                            Thread.sleep(2000); 
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                    }
                                
                                }
                            }
                        }
                    }


                }
            } else {
                System.out.println("No search results");
            }

        } catch (Exception e) {
            System.err.println("Error searching for founders" + e.getMessage());
        }

        return founders;
    }

    /*
     * Gets the HTML content from a URL and extracts the body
     * Paramters: the url to get the HTML from
     * Returns: the HTML content within the body tags (with the scripts removed) OR and empty string if the fetch fails
     */
    private static String fetchBodyContent(String url) {
        try {
            HttpClient client = HttpClient.newHttpClient(); 
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String htmlContent = response.body(); 

                // finds the body tags in HTML
                int bodyStart = htmlContent.indexOf("<body"); 
                int bodyEnd = htmlContent.indexOf("</body>");

                if (bodyStart != -1 && bodyEnd != -1) {
                    // extracts the content between the body tags
                    int bodyContentStart = htmlContent.indexOf(">", bodyStart) + 1; 

                    String bodyContent = htmlContent.substring(bodyContentStart, bodyEnd); 
                    // removes the unnecessary script tags (they are of no value)
                    bodyContent = removeScriptTags(bodyContent); 

                    // DEBUG: System.out.println("Fetched " + bodyContent.length() + " characters");
                    return bodyContent;
                } else {
                    // DEBUG: System.out.println("No body tags found");
                }
            } else {
                // DEBUG: System.out.println("Failed to fetch"); 
            }
        } catch (Exception e) {
            System.out.println("Error fetching URL" + e.getMessage());
        }

        return "";
    }
     /*
      * Removes all the script tags and their contents from HTML
      * Reduces the amount of text that is def into OpenAI and improves accuracy
      * Paramters: the HTML content as a String
      * HTML with the script tags removed
      */
    private static String removeScriptTags(String html) {

        // keep removing those internal script tags until none are left
        while (html.contains("<script")) {
            int scriptStart = html.indexOf("<script"); 
            int scriptEnd = html.indexOf("</script", scriptStart); 

            if (scriptStart != -1 && scriptEnd != -1) {

                // remove everything PLUS the tags
                html = html.substring(0, scriptStart) + html.substring(scriptEnd + 9); 
            } else {
                break;
            }
        }

        return html;
    }

    /*
     * Splits the text into chunks of a specifized size so that the text stays within OpenAI's token limits
     * Paramters: 
     * - text to split into chunks
     * - size of the chunk
     * Returns: a list of text chunks 
     */
    private static List<String> splitIntoChunks(String text, int chunkSize) {

        List<String> chunks = new ArrayList<>();

        for (int i = 0; i < text.length(); i+=chunkSize) {
            int endIndex = Math.min(i + chunkSize, text.length()); 
            chunks.add(text.substring(i, endIndex)); 
        }

        return chunks;

    }
    /*
     * Uses OpenAI's API to extract the founder names from a text
     * Parameters:
     * - the text to analyze 
     * - the company name that will get passed in the prompt
     * Returns a list of founder names if found (empty list if none are found)
     */
    private static List<String> getFounderNames(String text, String companyName) {
        List<String> names = new ArrayList<>(); 
        try {
            
            // constructed a thorough prompt 
            String prompt = "Extract only the names of the company's founders (people who started/co-founded the company: " + companyName + "). Do NOT include employees with job titles like 'Founding Engineer' or 'Founding Designer' - only the actual company founders. Return just the founder names separated by commas, nothing else. Names shouldn't be more than 3 or 4 words long, so if it is more than that it is probably not what I am looking for. If no company founders mentioned, return 'NONE'.\n\nText: " + text;

            // build the API request 
            JSONObject requestBody = new JSONObject(); 
            requestBody.put("model", "gpt-4.1-mini");

            JSONArray messages = new JSONArray(); 
            JSONObject message = new JSONObject(); 
            message.put("role", "user"); 
            message.put("content", prompt); 
            messages.put(message); 

            requestBody.put("messages", messages); 
            requestBody.put("temperature", 0); 
            requestBody.put("max_tokens", 100); 

            // send the actual API request

            HttpClient client = HttpClient.newHttpClient(); 
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create("https://api.openai.com/v1/chat/completions")).header("Content-Type", "application/json").header("Authorization", "Bearer " + OPEN_AI_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString())).build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // parse the response 
                JSONObject jsonResponse = new JSONObject(response.body());
                String content = jsonResponse.getJSONArray("choices")
                                .getJSONObject(0).getJSONObject("message").getString("content").trim(); 
                
                // extract the founder names from the comma seperated list 
                if (!content.equals("NONE")) {
                    String[] foundNames = content.split(","); 
                    for (String name : foundNames) {
                        String properName = name.trim(); 

                        // filters out empty strings 
                        if(!properName.isEmpty() && !properName.equalsIgnoreCase("NONE") && !names.contains(properName)) {
                            names.add(properName); 
                            System.out.println("Found founder: " + properName);
                        }
                    }
                }
            } else {
                // DEBUG: System.out.println(text.length());
                System.out.println("OpenAI returned non-200 response code" + response.statusCode()); 
            }

        } catch (Exception e) {
            System.err.println("Problem with OpenAI" + e.getMessage());
        }
    




        return names; 
    }

    /*
     * Writes the results to a JSON file in the format provided 
     * Parameters: 
     * -hashmap of the results
     * -output path for where to store the file
     * -throws an exception if writing fails
     */
    private static void writeResultsToJSON(Map<String, List<String>> results, String outputPath) throws IOException {
        JSONObject jsonOutput = new JSONObject(); 

        for (Map.Entry<String, List<String>> entry : results.entrySet()) {
            String companyName = entry.getKey(); 
            List<String> founders = entry.getValue();

            JSONArray foundersArray = new JSONArray(founders); 
            jsonOutput.put(companyName, foundersArray);
        }

        String jsonString = jsonOutput.toString(2); 
        Files.write(Paths.get(outputPath), jsonString.getBytes()); 

    }

    /*
     * Private class to store company information
     */
    private static class CompanyData {
        String name; 
        String url; 
    
        CompanyData(String name, String url) {
            this.name = name; 
            this.url = url; 
        }
    }
}


