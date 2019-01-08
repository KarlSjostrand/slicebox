(function () {
   'use strict';
}());

angular.module('slicebox.adminForwarding', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/admin/forwarding', {
    templateUrl: '/assets/partials/adminForwarding.html',
    controller: 'AdminForwardingCtrl'
  });
})

.controller('AdminForwardingCtrl', function($scope, $http, $mdDialog, openAddEntityModal, openDeleteEntitiesModalFunction) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/forwarding/rules/', 'forwarding rule(s)')
            }
        ];

    $scope.callbacks = {};
  
    // Scope functions
    $scope.loadRulesPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/forwarding/rules?startindex=' + startIndex + '&count=' + count);
    };

    $scope.addRuleButtonClicked = function() {
        openAddEntityModal('addForwardingRuleModalContent.html', 'AddForwardingRuleModalCtrl', '/api/forwarding/rules', 'Forwarding rule', $scope.callbacks.rulesTable);
    };
})

.controller('AddForwardingRuleModalCtrl', function($scope, $mdDialog, $http) {

    $scope.uiState = {
        sources: [],
        boxes: [],
        source: null,
        destination: null,
        keepImages: true
    };

    // Scope functions

    $scope.loadSources = function() {
        return $http.get("/api/sources").then(function (response) {
            $scope.uiState.sources = response.data;
        });
    };

    $scope.loadDestinations = function() {
        return $http.get("/api/destinations").then(function (response) {
            $scope.uiState.destinations = response.data;
        });
    };

    $scope.addButtonClicked = function() {
        $mdDialog.hide({ 
            id: -1,
            source: $scope.uiState.source, 
            destination: $scope.uiState.destination, 
            keepImages: $scope.uiState.keepImages 
        });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };

});