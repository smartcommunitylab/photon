-- selected max place_id then multiple different ways on same street are founded
 
CREATE TABLE placex_solr AS SELECT *
   FROM placex
  WHERE (placex.place_id IN ( SELECT max(placex.place_id) AS max
           FROM placex
          GROUP BY placex.parent_place_id, placex.name -> 'name'::text, placex.class, placex.type));