
################################################################################################
#
#  This script will read the raw topology file (./data/mosaic_topology.csv) and 
#  map each unique household to a device topology
#
################################################################################################

import re,csv

input_file  = "./data/mosaic_topology.csv"
output_file = "./data/mosaic_topology_mapped.csv"

read_input_file  = csv.reader(open(input_file, 'rb'))
read_output_file = open(output_file,"wb")

header = read_input_file.next()
rows   = []
for row in read_input_file:
    rows.append(row)

results = []

for i in rows:
    
    id          = i[0]                  # ID
    gnis_id     = i[2]                  # GNIS_ID
    latitude    = i[3]                  # Latitude
    longitude   = i[4]                  # Longitude
    house_id    = i[5]                  # House_ID
    node_number = i[6]                  # Node_Number
    upstream_id = i[7]                  # Upstream_Device_ID
    
    output      = str(id) + ',' + str(house_id) + ',' + str(gnis_id) + ',' + str(latitude) + ',' + str(longitude) + ',' + str(node_number) + ','   # House_ID (formatted for output)
    
    device_upstream_id_out = 'start'
    
    if house_id != '':
        print '[ INFO ] Scanning household ID: ' + str(house_id)
        
        while device_upstream_id_out != '':
            
            match_found = 'no'        
            for j in rows:
                
                device_id           = j[0]
                device_type         = j[1]
                device_upstream_id  = j[7]
                
                if (upstream_id == device_id) and (match_found == 'no'):
                    
                    #output = output + ',' + str(device_id) + '_' + str(device_type)
                    output = output + str(device_id) + '_' + str(device_type) + '|'
                    upstream_id = device_upstream_id
                    match_found = 'yes'
                    device_upstream_id_out = device_upstream_id
        
        output = re.sub('\|$','',output)
        #results.append(output)
        read_output_file.write(output + '\n')


read_output_file.close()
print '[ INFO ] Topology map has been written to ' + str(output_file)


#ZEND
