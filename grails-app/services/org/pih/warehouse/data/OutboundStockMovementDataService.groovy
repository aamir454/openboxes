/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package org.pih.warehouse.data

import org.joda.time.LocalDate
import org.pih.warehouse.core.Location
import org.pih.warehouse.importer.ImportDataCommand
import org.pih.warehouse.product.Product
import org.pih.warehouse.requisition.Requisition
import org.pih.warehouse.requisition.RequisitionItem
import org.pih.warehouse.requisition.RequisitionStatus
import org.pih.warehouse.requisition.RequisitionType
import org.springframework.validation.BeanPropertyBindingResult
import org.pih.warehouse.auth.AuthService

class OutboundStockMovementDataService {

    Boolean validateData(ImportDataCommand command) {
        log.info "Validate data " + command.filename
        command.data.eachWithIndex { params, index ->
            if (!params?.origin) {
                throw new IllegalArgumentException("Row ${index + 1}: Origin is required")
            }
            if (!params?.destination) {
                throw new IllegalArgumentException("Row ${index + 1}: Destination is required")
            }
            if (!params?.quantity) {
                throw new IllegalArgumentException("Row ${index + 1}: Requested Quantity is required")
            }
            RequisitionItem requisitionItem = buildRequisitionItem(params)
            if (!requisitionItem.validate()) {
                requisitionItem.errors.each { BeanPropertyBindingResult error ->
                    command.errors.reject("${index + 1}: ${requisitionItem} ${error.getFieldError()}")
                }
            }
        }
    }

    void importData(ImportDataCommand command) {
        log.info "Import data " + command.filename
        command.data.eachWithIndex {params, index ->
            RequisitionItem requisitionItem = buildRequisitionItem(params)
            if(requisitionItem.validate()){
                requisitionItem.save(failOnError: true)
            }
        }
    }


    RequisitionItem buildRequisitionItem(Map params) {
        String productCode = params.productCode
        Product product = Product.findByProductCode(productCode)
        if(!product) {
            throw new IllegalArgumentException("Product not found for ${productCode}")
        }

        def quantityRequested = params.quantity as Integer
        if (!(quantityRequested > 0)) {
            throw new IllegalArgumentException("Requested quantity should be greater than 0")
        }

        def deliveryDate = params.deliveryDate
        if (!isDateOneWeekFromNow(deliveryDate)) {
            throw new IllegalArgumentException("Delivery date must be after seven days from now")
        }

        def comments = params.destination
        def requestNumber = params.requestNumber
        def requisition = Requisition.findByRequestNumber(requestNumber)
        if (!requisition) {
            String name = "Outbound Order ${requestNumber}"
            requisition = new Requisition(
                    name: name,
                    requestNumber: requestNumber,
                    status: RequisitionStatus.CREATED
            )
            requisition.origin = findLocationByLocationNumber(params.origin)
            requisition.destination = findLocationByLocationNumber(params.destination)
            requisition.type = RequisitionType.DEFAULT
            requisition.description = name
            requisition.requestedDeliveryDate = deliveryDate.toDate()
            requisition.dateRequested = new Date()
            requisition.requestedBy = AuthService.currentUser.get()
            requisition.save(failOnError: true)
        }

        def requisitionItem = RequisitionItem.createCriteria().get {
            eq 'product' , product
            eq "requisition", requisition
        }
        if (!requisitionItem) {
            requisitionItem = new RequisitionItem()
        }

        requisitionItem.product = product
        requisitionItem.quantity = quantityRequested
        requisitionItem.comment = comments

        requisition.addToRequisitionItems(requisitionItem)

        return requisitionItem
    }

    Location findLocationByLocationNumber(String locationNumber) {
        Location location = Location.findByLocationNumber(locationNumber)
        if (!location) {
            throw new IllegalArgumentException("Location not found for location number ${locationNumber}")
        }
        return location
    }

    boolean isDateOneWeekFromNow(def date) {
        LocalDate today = LocalDate.now()
        LocalDate oneWeekFromNow = today.plusDays(7)
        if(date > oneWeekFromNow) {
            return true
        }
        return false
    }
}
