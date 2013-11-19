-- selected max place_id then multiple different ways on same street are founded
 
CREATE TABLE placex_solr AS SELECT *
   FROM placex
  WHERE (placex.place_id IN ( SELECT max(placex.place_id) AS max
           FROM placex
          GROUP BY placex.parent_place_id, placex.name -> 'name'::text, placex.class, placex.type));
          
COMMENT ON TABLE placex_solr IS 'table use to create solr index, only a way describing the same street'