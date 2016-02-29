/*
 * Copyright 2016 Lars Edenbrandt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.metadata

import scala.slick.driver.JdbcProfile
import scala.slick.jdbc.{ GetResult, StaticQuery => Q }
import se.nimsa.sbx.dicom.DicomHierarchy._
import se.nimsa.sbx.dicom.DicomPropertyValue._
import se.nimsa.sbx.seriestype.SeriesTypeDAO
import se.nimsa.sbx.seriestype.SeriesTypeProtocol._
import scala.slick.jdbc.meta.MTable
import se.nimsa.sbx.app.GeneralProtocol._
import MetaDataProtocol._

class PropertiesDAO(val driver: JdbcProfile) {
  import driver.simple._
  import MetaDataDAO._
  
  val metaDataDao = new MetaDataDAO(driver)
  val seriesTypeDao = new SeriesTypeDAO(driver)

  // *** Sources ***

  private val toSeriesSource = (id: Long, sourceType: String, sourceName: String, sourceId: Long) => SeriesSource(id, Source(SourceType.withName(sourceType), sourceName, sourceId))

  private val fromSeriesSource = (seriesSource: SeriesSource) => Option((seriesSource.id, seriesSource.source.sourceType.toString, seriesSource.source.sourceName, seriesSource.source.sourceId))

  private class SeriesSources(tag: Tag) extends Table[SeriesSource](tag, "SeriesSources") {
    def id = column[Long]("id", O.PrimaryKey)
    def sourceType = column[String]("sourcetype")
    def sourceName = column[String]("sourcename")
    def sourceId = column[Long]("sourceid")
    def * = (id, sourceType, sourceName, sourceId) <> (toSeriesSource.tupled, fromSeriesSource)

    def seriesSourceToImageFKey = foreignKey("seriesSourceToImageFKey", id, metaDataDao.seriesQuery)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
    def seriesIdJoin = metaDataDao.seriesQuery.filter(_.id === id)
  }

  private val seriesSourceQuery = TableQuery[SeriesSources]

  // *** Tags ***

  private val toSeriesTag = (id: Long, name: String) => SeriesTag(id, name)

  private val fromSeriesTag = (seriesTag: SeriesTag) => Option((seriesTag.id, seriesTag.name))

  class SeriesTagTable(tag: Tag) extends Table[SeriesTag](tag, "SeriesTags") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def name = column[String]("name")
    def idxUniqueName = index("idx_unique_series_tag_name", name, unique = true)
    def * = (id, name) <> (toSeriesTag.tupled, fromSeriesTag)
  }

  private val seriesTagQuery = TableQuery[SeriesTagTable]

  private val toSeriesSeriesTagRule = (seriesId: Long, seriesTagId: Long) => SeriesSeriesTag(seriesId, seriesTagId)

  private val fromSeriesSeriesTagRule = (seriesSeriesTag: SeriesSeriesTag) => Option((seriesSeriesTag.seriesId, seriesSeriesTag.seriesTagId))

  private class SeriesSeriesTagTable(tag: Tag) extends Table[SeriesSeriesTag](tag, "SeriesSeriesTags") {
    def seriesId = column[Long]("seriesid")
    def seriesTagId = column[Long]("seriestagid")
    def pk = primaryKey("pk_tag", (seriesId, seriesTagId))
    def fkSeries = foreignKey("fk_series_seriesseriestag", seriesId, metaDataDao.seriesQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def fkSeriesType = foreignKey("fk_seriestag_seriesseriestag", seriesTagId, seriesTagQuery)(_.id, onDelete = ForeignKeyAction.Cascade)
    def * = (seriesId, seriesTagId) <> (toSeriesSeriesTagRule.tupled, fromSeriesSeriesTagRule)
  }

  private val seriesSeriesTagQuery = TableQuery[SeriesSeriesTagTable]

  // Setup

  def create(implicit session: Session) = {
    if (MTable.getTables("SeriesSources").list.isEmpty) seriesSourceQuery.ddl.create
    if (MTable.getTables("SeriesTags").list.isEmpty) seriesTagQuery.ddl.create
    if (MTable.getTables("SeriesSeriesTags").list.isEmpty) seriesSeriesTagQuery.ddl.create
  }

  def drop(implicit session: Session) =
    if (MTable.getTables("SeriesTags").list.size > 0)
      (seriesSourceQuery.ddl ++ seriesTagQuery.ddl ++ seriesSeriesTagQuery.ddl).drop

  def clear(implicit session: Session) = {
    seriesSourceQuery.delete
    seriesTagQuery.delete
    seriesSeriesTagQuery.delete
  }

  // Functions

  def insertSeriesSource(seriesSource: SeriesSource)(implicit session: Session): SeriesSource = {
    seriesSourceQuery += seriesSource
    seriesSource
  }

  def seriesSourceById(seriesId: Long)(implicit session: Session): Option[SeriesSource] =
    seriesSourceQuery.filter(_.id === seriesId).firstOption

  def seriesSources(implicit session: Session): List[SeriesSource] = seriesSourceQuery.list

  def insertSeriesTag(seriesTag: SeriesTag)(implicit session: Session): SeriesTag = {
    val generatedId = (seriesTagQuery returning seriesTagQuery.map(_.id)) += seriesTag
    seriesTag.copy(id = generatedId)
  }

  def seriesTagForName(name: String)(implicit session: Session): Option[SeriesTag] =
    seriesTagQuery.filter(_.name === name).firstOption

  def updateSeriesTag(seriesTag: SeriesTag)(implicit session: Session): Unit =
    seriesTagQuery.filter(_.id === seriesTag.id).update(seriesTag)

  def listSeriesSources(implicit session: Session): List[SeriesSource] =
    seriesSourceQuery.list

  def listSeriesTags(implicit session: Session): List[SeriesTag] =
    seriesTagQuery.list

  def removeSeriesTag(seriesTagId: Long)(implicit session: Session): Unit =
    seriesTagQuery.filter(_.id === seriesTagId).delete

  def insertSeriesSeriesTag(seriesSeriesTag: SeriesSeriesTag)(implicit session: Session): SeriesSeriesTag = {
    seriesSeriesTagQuery += seriesSeriesTag
    seriesSeriesTag
  }

  def listSeriesSeriesTagsForSeriesId(seriesId: Long)(implicit session: Session): List[SeriesSeriesTag] =
    seriesSeriesTagQuery.filter(_.seriesId === seriesId).list

  def listSeriesSeriesTagsForSeriesTagId(seriesTagId: Long)(implicit session: Session): List[SeriesSeriesTag] =
    seriesSeriesTagQuery.filter(_.seriesTagId === seriesTagId).list

  def seriesSeriesTagForSeriesTagIdAndSeriesId(seriesTagId: Long, seriesId: Long)(implicit session: Session): Option[SeriesSeriesTag] =
    seriesSeriesTagQuery.filter(_.seriesTagId === seriesTagId).filter(_.seriesId === seriesId).firstOption

  def removeSeriesSeriesTag(seriesTagId: Long, seriesId: Long)(implicit session: Session): Unit =
    seriesSeriesTagQuery.filter(_.seriesTagId === seriesTagId).filter(_.seriesId === seriesId).delete

  def seriesTagsForSeries(seriesId: Long)(implicit session: Session): List[SeriesTag] = {
    seriesSeriesTagQuery.filter(_.seriesId === seriesId)
      .innerJoin(seriesTagQuery).on(_.seriesTagId === _.id)
      .map(_._2).list
  }

  def addAndInsertSeriesTagForSeriesId(seriesTag: SeriesTag, seriesId: Long)(implicit session: Session): SeriesTag = {
    val dbSeriesTag = seriesTagForName(seriesTag.name).getOrElse(insertSeriesTag(seriesTag))
    val dbSeriesSeriesTag =
      seriesSeriesTagForSeriesTagIdAndSeriesId(dbSeriesTag.id, seriesId)
        .getOrElse(insertSeriesSeriesTag(SeriesSeriesTag(seriesId, dbSeriesTag.id)))
    dbSeriesTag
  }

  def cleanupSeriesTag(seriesTagId: Long)(implicit session: Session) = {
    val otherSeriesWithSameTag = listSeriesSeriesTagsForSeriesTagId(seriesTagId)
    if (otherSeriesWithSameTag.isEmpty)
      removeSeriesTag(seriesTagId)
  }

  def removeAndCleanupSeriesTagForSeriesId(seriesTagId: Long, seriesId: Long)(implicit session: Session): Unit = {
    removeSeriesSeriesTag(seriesTagId, seriesId)
    cleanupSeriesTag(seriesTagId)
  }

  def deleteFully(image: Image)(implicit session: Session): (Option[Patient], Option[Study], Option[Series], Option[Image]) = {
    val imagesDeleted = metaDataDao.deleteImage(image.id)
    val pssMaybe = metaDataDao.seriesById(image.seriesId)
      .filter(series => metaDataDao.imagesForSeries(0, 2, series.id).isEmpty)
      .map(series => deleteFully(series))
      .getOrElse((None, None, None))
    val imageMaybe = if (imagesDeleted == 0) None else Some(image)
    (pssMaybe._1, pssMaybe._2, pssMaybe._3, imageMaybe)
  }

  def deleteFully(series: Series)(implicit session: Session): (Option[Patient], Option[Study], Option[Series]) = {
    val seriesSeriesTags = seriesTagsForSeries(series.id)
    val (pMaybe, stMaybe, seMaybe) = metaDataDao.deleteFully(series)
    seriesSeriesTags.foreach(seriesTag => cleanupSeriesTag(seriesTag.id))
    (pMaybe, stMaybe, seMaybe)
  }

  def flatSeries(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String], sourceRefs: Seq[SourceRef], seriesTypeIds: Seq[Long], seriesTagIds: Seq[Long])(implicit session: Session): List[FlatSeries] = {

    if (isWithAdvancedFiltering(sourceRefs, seriesTypeIds, seriesTagIds)) {

      orderBy.foreach(metaDataDao.checkColumnExists(_, "Patients", "Studies", "Series"))

      implicit val getResult = metaDataDao.flatSeriesGetResult

      val query =
        metaDataDao.flatSeriesBasePart +
          propertiesJoinPart(sourceRefs, seriesTypeIds, seriesTagIds) +
          " where" +
          metaDataDao.flatSeriesFilterPart(filter) +
          andPart(filter, sourceRefs) +
          sourcesPart(sourceRefs) +
          andPart(filter, sourceRefs, seriesTypeIds) +
          seriesTypesPart(seriesTypeIds) +
          andPart(filter, sourceRefs, seriesTypeIds, seriesTagIds) +
          seriesTagsPart(seriesTagIds) +
          orderByPart(orderBy, orderAscending) +
          pagePart(startIndex, count)

      Q.queryNA(query).list

    } else
      metaDataDao.flatSeries(startIndex, count, orderBy, orderAscending, filter)
  }

  def propertiesJoinPart(sourceRefs: Seq[SourceRef], seriesTypeIds: Seq[Long], seriesTagIds: Seq[Long]) =
    singlePropertyJoinPart(sourceRefs, """ inner join "SeriesSources" on "Series"."id" = "SeriesSources"."id"""") +
      singlePropertyJoinPart(seriesTypeIds, """ inner join "SeriesSeriesTypes" on "Series"."id" = "SeriesSeriesTypes"."seriesid"""") +
      singlePropertyJoinPart(seriesTagIds, """ inner join "SeriesSeriesTags" on "Series"."id" = "SeriesSeriesTags"."seriesid"""")

  def singlePropertyJoinPart(property: Seq[_ <: Any], part: String) = if (property.isEmpty) "" else part

  def patients(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, filter: Option[String], sourceRefs: Seq[SourceRef], seriesTypeIds: Seq[Long], seriesTagIds: Seq[Long])(implicit session: Session): List[Patient] = {

    if (isWithAdvancedFiltering(sourceRefs, seriesTypeIds, seriesTagIds)) {

      orderBy.foreach(metaDataDao.checkColumnExists(_, "Patients"))

      implicit val getResult = metaDataDao.patientsGetResult

      val query =
        patientsBasePart +
          propertiesJoinPart(sourceRefs, seriesTypeIds, seriesTagIds) +
          " where" +
          metaDataDao.patientsFilterPart(filter) +
          andPart(filter, sourceRefs) +
          sourcesPart(sourceRefs) +
          andPart(filter, sourceRefs, seriesTypeIds) +
          seriesTypesPart(seriesTypeIds) +
          andPart(filter, sourceRefs, seriesTypeIds, seriesTagIds) +
          seriesTagsPart(seriesTagIds) +
          orderByPart(orderBy, orderAscending) +
          pagePart(startIndex, count)

      Q.queryNA(query).list

    } else
      metaDataDao.patients(startIndex, count, orderBy, orderAscending, filter)
  }

  def parseQueryOrder(optionalOrder: Option[QueryOrder]) =
    (optionalOrder.map(_.orderBy), optionalOrder.map(_.orderAscending).getOrElse(true))

  def wherePart(arrays: Seq[_ <: Any]*) =
    if (arrays.exists(!_.isEmpty))
      " where "
    else
      ""

  def queryMainPart(startIndex: Long, count: Long, orderBy: Option[String], orderAscending: Boolean, sourceRefs: Seq[SourceRef], seriesTypeIds: Seq[Long], seriesTagIds: Seq[Long], queryProperties: Seq[QueryProperty]) =
    propertiesJoinPart(sourceRefs, seriesTypeIds, seriesTagIds) +
      wherePart(queryProperties, sourceRefs, seriesTypeIds, seriesTagIds) +
      queryPart(queryProperties) +
      andPart(queryProperties, sourceRefs) +
      sourcesPart(sourceRefs) +
      andPart(queryProperties, sourceRefs, seriesTypeIds) +
      seriesTypesPart(seriesTypeIds) +
      andPart(queryProperties, sourceRefs, seriesTypeIds, seriesTagIds) +
      seriesTagsPart(seriesTagIds) +
      orderByPart(orderBy, orderAscending) +
      pagePart(startIndex, count)

  def queryPatients(startIndex: Long, count: Long, optionalOrder: Option[QueryOrder], queryProperties: Seq[QueryProperty], optionalFilters: Option[QueryFilters])(implicit session: Session): List[Patient] = {

    val (orderBy, orderAscending) = parseQueryOrder(optionalOrder)

    optionalFilters.filter { filters =>
      isWithAdvancedFiltering(filters.seriesTagIds, filters.seriesTypeIds, filters.sourceRefs)
    }.map { filters =>

      orderBy.foreach(metaDataDao.checkColumnExists(_, "Patients"))
      queryProperties.foreach(qp => metaDataDao.checkColumnExists(qp.propertyName, "Patients", "Studies", "Series"))

      implicit val getResult = metaDataDao.patientsGetResult

      val query =
        metaDataDao.queryPatientsSelectPart +
          queryMainPart(startIndex, count, orderBy, orderAscending, filters.sourceRefs, filters.seriesTypeIds, filters.seriesTagIds, queryProperties)

      Q.queryNA(query).list

    }.getOrElse {
      metaDataDao.queryPatients(startIndex, count, orderBy, orderAscending, queryProperties)
    }

  }

  def queryStudies(startIndex: Long, count: Long, optionalOrder: Option[QueryOrder], queryProperties: Seq[QueryProperty], optionalFilters: Option[QueryFilters])(implicit session: Session): List[Study] = {

    val (orderBy, orderAscending) = parseQueryOrder(optionalOrder)

    optionalFilters.filter { filters =>
      isWithAdvancedFiltering(filters.seriesTagIds, filters.seriesTypeIds, filters.sourceRefs)
    }.map { filters =>

      orderBy.foreach(metaDataDao.checkColumnExists(_, "Studies"))
      queryProperties.foreach(qp => metaDataDao.checkColumnExists(qp.propertyName, "Patients", "Studies", "Series"))

      implicit val getResult = metaDataDao.studiesGetResult

      val query =
        metaDataDao.queryStudiesSelectPart +
          queryMainPart(startIndex, count, orderBy, orderAscending, filters.sourceRefs, filters.seriesTypeIds, filters.seriesTagIds, queryProperties)

      Q.queryNA(query).list

    }.getOrElse {
      metaDataDao.queryStudies(startIndex, count, orderBy, orderAscending, queryProperties)
    }

  }

  def querySeries(startIndex: Long, count: Long, optionalOrder: Option[QueryOrder], queryProperties: Seq[QueryProperty], optionalFilters: Option[QueryFilters])(implicit session: Session): List[Series] = {

    val (orderBy, orderAscending) = parseQueryOrder(optionalOrder)

    optionalFilters.filter { filters =>
      isWithAdvancedFiltering(filters.seriesTagIds, filters.seriesTypeIds, filters.sourceRefs)
    }.map { filters =>

      orderBy.foreach(metaDataDao.checkColumnExists(_, "Series"))
      queryProperties.foreach(qp => metaDataDao.checkColumnExists(qp.propertyName, "Patients", "Studies", "Series"))

      implicit val getResult = metaDataDao.seriesGetResult

      val query =
        metaDataDao.querySeriesSelectPart +
          queryMainPart(startIndex, count, orderBy, orderAscending, filters.sourceRefs, filters.seriesTypeIds, filters.seriesTagIds, queryProperties)

      Q.queryNA(query).list

    }.getOrElse {
      metaDataDao.querySeries(startIndex, count, orderBy, orderAscending, queryProperties)
    }

  }

  def queryImages(startIndex: Long, count: Long, optionalOrder: Option[QueryOrder], queryProperties: Seq[QueryProperty], optionalFilters: Option[QueryFilters])(implicit session: Session): List[Image] = {

    val (orderBy, orderAscending) = parseQueryOrder(optionalOrder)

    optionalFilters.filter { filters =>
      isWithAdvancedFiltering(filters.seriesTagIds, filters.seriesTypeIds, filters.sourceRefs)
    }.map { filters =>

      orderBy.foreach(metaDataDao.checkColumnExists(_, "Images"))
      queryProperties.foreach(qp => metaDataDao.checkColumnExists(qp.propertyName, "Patients", "Studies", "Series", "Images"))

      implicit val getResult = metaDataDao.imagesGetResult

      val query =
        metaDataDao.queryImagesSelectPart +
          queryMainPart(startIndex, count, orderBy, orderAscending, filters.sourceRefs, filters.seriesTypeIds, filters.seriesTagIds, queryProperties)

      Q.queryNA(query).list

    }.getOrElse {
      metaDataDao.queryImages(startIndex, count, orderBy, orderAscending, queryProperties)
    }

  }

  def queryFlatSeries(startIndex: Long, count: Long, optionalOrder: Option[QueryOrder], queryProperties: Seq[QueryProperty], optionalFilters: Option[QueryFilters])(implicit session: Session): List[FlatSeries] = {

    val (orderBy, orderAscending) = parseQueryOrder(optionalOrder)

    optionalFilters.filter { filters =>
      isWithAdvancedFiltering(filters.seriesTagIds, filters.seriesTypeIds, filters.sourceRefs)
    }.map { filters =>

      orderBy.foreach(metaDataDao.checkColumnExists(_, "Patients", "Studies", "Series"))
      queryProperties.foreach(qp => metaDataDao.checkColumnExists(qp.propertyName, "Patients", "Studies", "Series"))

      implicit val getResult = metaDataDao.flatSeriesGetResult

      val query =
        metaDataDao.flatSeriesBasePart +
          queryMainPart(startIndex, count, orderBy, orderAscending, filters.sourceRefs, filters.seriesTypeIds, filters.seriesTagIds, queryProperties)

      Q.queryNA(query).list

    }.getOrElse {
      metaDataDao.queryFlatSeries(startIndex, count, orderBy, orderAscending, queryProperties)
    }

  }

  def isWithAdvancedFiltering(arrays: Seq[_ <: Any]*) = arrays.exists(!_.isEmpty)

  def patientsBasePart = s"""select distinct("Patients"."id"),
       "Patients"."patientName","Patients"."patientID","Patients"."patientBirthDate","Patients"."patientSex"
       from "Series" 
       inner join "Patients" on "Studies"."patientId" = "Patients"."id"
       inner join "Studies" on "Series"."studyId" = "Studies"."id""""

  def andPart(target: Seq[_ <: Any]) = if (!target.isEmpty) " and" else ""

  def andPart(array: Seq[_ <: Any], target: Seq[_ <: Any]) = if (!array.isEmpty && !target.isEmpty) " and" else ""

  def andPart(array1: Seq[_ <: Any], array2: Seq[_ <: Any], target: Seq[_ <: Any]) = if ((!array1.isEmpty || !array2.isEmpty) && !target.isEmpty) " and" else ""

  def andPart(array1: Seq[_ <: Any], array2: Seq[_ <: Any], array3: Seq[_ <: Any], target: Seq[_ <: Any]) = if ((!array1.isEmpty || !array2.isEmpty || !array3.isEmpty) && !target.isEmpty) " and" else ""

  def andPart(option: Option[Any], target: Seq[_ <: Any]) = if (option.isDefined && !target.isEmpty) " and" else ""

  def andPart(option: Option[Any], array: Seq[_ <: Any], target: Seq[_ <: Any]) = if ((option.isDefined || !array.isEmpty) && !target.isEmpty) " and" else ""

  def andPart(option: Option[Any], array1: Seq[_ <: Any], array2: Seq[_ <: Any], target: Seq[_ <: Any]) = if ((option.isDefined || !array1.isEmpty || !array2.isEmpty) && !target.isEmpty) " and" else ""

  def sourcesPart(sourceRefs: Seq[SourceRef]) =
    if (sourceRefs.isEmpty)
      ""
    else
      " (" + sourceRefs.map(sourceTypeId =>
        s""""SeriesSources"."sourcetype" = '${sourceTypeId.sourceType}' and "SeriesSources"."sourceid" = ${sourceTypeId.sourceId}""")
        .mkString(" or ") + ")"

  def seriesTypesPart(seriesTypeIds: Seq[Long]) =
    if (seriesTypeIds.isEmpty)
      ""
    else
      " (" + seriesTypeIds.map(seriesTypeId =>
        s""""SeriesSeriesTypes"."seriestypeid" = $seriesTypeId""")
        .mkString(" or ") + ")"

  def seriesTagsPart(seriesTagIds: Seq[Long]) =
    if (seriesTagIds.isEmpty)
      ""
    else
      " (" + seriesTagIds.map(seriesTagId =>
        s""""SeriesSeriesTags"."seriestagid" = $seriesTagId""")
        .mkString(" or ") + ")"

  def studiesGetResult = GetResult(r =>
    Study(r.nextLong, r.nextLong, StudyInstanceUID(r.nextString), StudyDescription(r.nextString), StudyDate(r.nextString), StudyID(r.nextString), AccessionNumber(r.nextString), PatientAge(r.nextString)))

  def studiesForPatient(startIndex: Long, count: Long, patientId: Long, sourceRefs: Seq[SourceRef], seriesTypeIds: Seq[Long], seriesTagIds: Seq[Long])(implicit session: Session): List[Study] = {

    if (isWithAdvancedFiltering(sourceRefs, seriesTypeIds, seriesTagIds)) {

      implicit val getResult = studiesGetResult

      val basePart = s"""select distinct("Studies"."id"),
        "Studies"."patientId","Studies"."studyInstanceUID","Studies"."studyDescription","Studies"."studyDate","Studies"."studyID","Studies"."accessionNumber","Studies"."patientAge"
        from "Series" 
        inner join "Studies" on "Series"."studyId" = "Studies"."id""""

      val wherePart = s"""
        where
        "Studies"."patientId" = $patientId"""

      val query = basePart +
        propertiesJoinPart(sourceRefs, seriesTypeIds, seriesTagIds) +
        wherePart +
        andPart(sourceRefs) +
        sourcesPart(sourceRefs) +
        andPart(seriesTypeIds) +
        seriesTypesPart(seriesTypeIds) +
        andPart(seriesTagIds) +
        seriesTagsPart(seriesTagIds) +
        pagePart(startIndex, count)

      Q.queryNA(query).list

    } else
      metaDataDao.studiesForPatient(startIndex, count, patientId)
  }

  def seriesGetResult = GetResult(r =>
    Series(r.nextLong, r.nextLong, SeriesInstanceUID(r.nextString), SeriesDescription(r.nextString), SeriesDate(r.nextString), Modality(r.nextString), ProtocolName(r.nextString), BodyPartExamined(r.nextString), Manufacturer(r.nextString), StationName(r.nextString), FrameOfReferenceUID(r.nextString)))

  def seriesForStudy(startIndex: Long, count: Long, studyId: Long, sourceRefs: Seq[SourceRef], seriesTypeIds: Seq[Long], seriesTagIds: Seq[Long])(implicit session: Session): List[Series] = {

    if (isWithAdvancedFiltering(sourceRefs, seriesTypeIds, seriesTagIds)) {

      implicit val getResult = seriesGetResult

      val basePart = s"""select distinct("Series"."id"),
        "Series"."studyId","Series"."seriesInstanceUID","Series"."seriesDescription","Series"."seriesDate","Series"."modality","Series"."protocolName","Series"."bodyPartExamined","Series"."manufacturer","Series"."stationName","Series"."frameOfReferenceUID"
        from "Series""""

      val wherePart = s"""
        where
        "Series"."studyId" = $studyId"""

      val query = basePart +
        propertiesJoinPart(sourceRefs, seriesTypeIds, seriesTagIds) +
        wherePart +
        andPart(sourceRefs) +
        sourcesPart(sourceRefs) +
        andPart(seriesTypeIds) +
        seriesTypesPart(seriesTypeIds) +
        andPart(seriesTagIds) +
        seriesTagsPart(seriesTagIds) +
        pagePart(startIndex, count)

      Q.queryNA(query).list

    } else
      metaDataDao.seriesForStudy(startIndex, count, studyId)
  }

}
