package de.komoot.photon.importer;

import com.neovisionaries.i18n.CountryCode;
import de.komoot.photon.importer.model.I18nName;
import de.komoot.photon.importer.model.NominatimEntry;
import de.komoot.photon.importer.model.NominatimEntryParent;
import de.komoot.photon.importer.model.OSM_TYPE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * handles fetching data from db and queries nominatim's geo index
 *
 * @author christoph
 */
public class IndexCrawler {
	private final static Logger LOGGER = LoggerFactory.getLogger(IndexCrawler.class);

	private final Connection connection;
	private final PreparedStatement statementSingle;
	private final PreparedStatement statementSingleOSM;
	private final PreparedStatement statementAddresses;

	private static final String SQL_TEMPLATE_HSTORE_NAME = "name->'name' as name, name->'ref' as name_ref, name->'place_name' as place_name, name->'short_name' as short_name, name->'official_name' as official_name  ";
	// data retrieved from table placex_solr and not from original placex
	// to avoid multiple instances of same way
	// see src/main/resources/placex_solr.sql for details
	private static final String SQL_TEMPLATE = "SELECT place_id, partition, osm_type, osm_id, class, type, %s , admin_level, housenumber, street, addr_place, isin, postcode, country_code, extratags, st_astext(centroid) as centroid, parent_place_id, linked_place_id, rank_address, rank_search, importance, indexed_status, indexed_date, wikipedia, geometry_sector, calculated_country_code FROM placex_solr ";
	private final List<String> languages;

	public IndexCrawler(Connection connection, List<String> languages) throws SQLException {
		this.connection = connection;
		this.connection.setAutoCommit(false);
		this.languages = languages;

		StringBuilder sqlSelectNames = new StringBuilder(SQL_TEMPLATE_HSTORE_NAME);
		for(String language : languages) {
			sqlSelectNames.append(" , name->'name:").append(language).append("' as name_").append(language);
		}

		String sql = String.format(SQL_TEMPLATE, sqlSelectNames.toString());
		statementSingle = connection.prepareStatement(sql + " WHERE place_id = ? ");
		statementSingleOSM = connection.prepareStatement(sql + " WHERE osm_id = ? AND osm_type = ? ");
		statementAddresses = connection.prepareStatement("SELECT place_id, osm_type, osm_id, " + sqlSelectNames + " , class, type, admin_level, rank_address FROM get_addressdata(?) WHERE isaddress ORDER BY rank_address DESC ");
	}

	/**
	 * completes an entry by passing all information provided by entry's parents
	 *
	 * @param entry
	 * @throws SQLException
	 */
	public void completeInformation(NominatimEntry entry) throws SQLException {
		List<NominatimEntryParent> parents = retrieveParents(entry);

		CountryCode country = getCountry(entry, parents);
		if(country == null) {
			LOGGER.error(String.format("unexpected: no country was defined for %s", entry));
		}

		// adopt information of parents
		for(NominatimEntryParent addressItem : parents) {
			addressItem.setCountry(country);
			entry.inheritProperties(addressItem);

			// fill places list
			if(addressItem.getPlaceId() != entry.getPlaceId()
					&& addressItem.isCountry() == false && addressItem.isStreet() == false
					&& addressItem.isPostcode() == false && addressItem.getName() != entry.getName()) {

				// handle special case of london
				if(addressItem.getOsmId() == 175342 && OSM_TYPE.R.equals(addressItem.getOsmType())) {
					// this item lies in Greater London
					if(entry.getCity() != null && false == entry.getCity().isNameless()) {
						// move "real" city to places
						entry.addPlace(entry.getCity());
					}
					entry.setCity(I18nName.LONDON);
				}

				if(addressItem.getName() != null && addressItem.getName().isNameless() == false) {
					entry.addPlace(addressItem.getName());
				}
			}
		}
	}

	/**
	 * get all parents from nominatim's index
	 *
	 * @param entry
	 * @return
	 * @throws SQLException
	 */
	private List<NominatimEntryParent> retrieveParents(NominatimEntry entry) throws SQLException {
		statementAddresses.setLong(1, entry.getPlaceId());
		ResultSet resultSet = statementAddresses.executeQuery();

		List<NominatimEntryParent> parents = new ArrayList<NominatimEntryParent>();
		while(resultSet.next()) {
			NominatimEntryParent addressItem = new NominatimEntryParent(resultSet, languages);
			parents.add(addressItem);
		}
		return parents;
	}

	/**
	 * get country either from entry itself or from one of its parents
	 *
	 * @param entry
	 * @param parents
	 * @return null if no country information available
	 */
	private CountryCode getCountry(NominatimEntry entry, List<NominatimEntryParent> parents) {
		if(entry.getCountry() != null) {
			return entry.getCountry();
		}

		for(NominatimEntryParent e : parents) {
			if(e.getCountry() != null) {
				return e.getCountry();
			}
		}

		return null;
	}

	/**
	 * get all records for xml conversions
	 *
	 * @param onlyBerlin
	 * @return
	 * @throws SQLException
	 */
	public ResultSet getAllRecords(boolean onlyBerlin) throws SQLException {
		PreparedStatement statementAll;

		StringBuilder sqlSelectNames = new StringBuilder(SQL_TEMPLATE_HSTORE_NAME);
		for(String language : languages) {
			sqlSelectNames.append(" , name->'name:").append(language).append("' as name_").append(language);
		}

		String sql = String.format(SQL_TEMPLATE, sqlSelectNames.toString());
		sql += " WHERE osm_type <> 'P' AND (name IS NOT NULL OR housenumber IS NOT NULL OR street IS NOT NULL OR postcode IS NOT NULL) AND centroid IS NOT NULL ";

		if(onlyBerlin) {
			sql += "AND st_contains(ST_GeomFromText('POLYGON ((12.718964 52.880734,13.92746 52.880734,13.92746 52.160455,12.718964 52.160455,12.718964 52.880734))', 4326), centroid) ";
		}

		sql += " ORDER BY st_x(ST_SnapToGrid(centroid, 0.1)), st_y(ST_SnapToGrid(centroid, 0.1)) "; // for performance reasons, ~15% faster

		statementAll = connection.prepareStatement(sql);
		statementAll.setFetchSize(100000);

		return statementAll.executeQuery();
	}

	/**
	 * searches for a single entry for a given osm id
	 *
	 * @param osmId
	 * @param osmType
	 * @return null if entry of OSM id cannot be found
	 * @throws SQLException
	 */
	public NominatimEntry getSingleOSM(long osmId, String osmType) throws SQLException {
		statementSingleOSM.setLong(1, osmId);
		statementSingleOSM.setString(2, osmType);
		ResultSet resultSet = statementSingleOSM.executeQuery();

		if(resultSet.next()) {
			NominatimEntry entry = new NominatimEntry(resultSet, languages);
			completeInformation(entry);
			return entry;
		} else {
			return null;
		}
	}
}
