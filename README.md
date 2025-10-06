# Company Founder Finder 
A Java Application that automatically extracts founder information from a list of companies using Google's Custom Search API and OpenAI's API 

## Overview 

This program takes a list of companies with their respective URL's and identifies the founder/cofounders by:
1. Searching each company's website for pages mentioning founders
2. Extracting and parsing HTML content from the pages that are relevant 
3. Using AI to extract the founder's names from the text
4. Exporting results to a JSON file

## Requirements

### Setup
Requires: `json-20231013.jar`

### API KEYS

1. Google Custom Search API Key 
2. Google Custom Search Engine ID
3. OpenAI's API Key

## Limitations 
- Depends on the company websites having accessbile founder information in the text (not in an embedded YouTube video for example)
- Google's search index may not include all the relevant pages
- Very new companies may not be well indexed 
- Some websites could block requests 
- API limits 
- Also because of chunking, on the very off chance the the text gets cut off in between the name of the founder, that name may not show up

