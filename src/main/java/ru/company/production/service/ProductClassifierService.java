package ru.company.production.service;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.*;
import ru.company.production.entity.*;
import ru.company.production.repository.*;
import java.util.*;
@Service @RequiredArgsConstructor
public class ProductClassifierService {
 private final ProductClassifierRepository classifiers;
 private final SensorCatalogRepository sensors;

 @Transactional(readOnly=true) public List<ProductClassifier> findAll(){return classifiers.findAllByOrderByCodeAsc();}
 @Transactional(readOnly=true) public List<SensorCatalog> findSensors(){return sensors.findAllByOrderByNameAsc();}
 @Transactional(readOnly=true) public List<ProductClassifier> inclusionOptions(Long excluded){
  return findAll().stream().filter(x->excluded==null||!excluded.equals(x.getId())).toList();
 }
 @Transactional(readOnly=true) public ClassifierForm getForm(Long id){
  ProductClassifier c=get(id); ClassifierForm f=new ClassifierForm();
  f.setId(c.getId());f.setCode(c.getCode());f.setProductType(c.getProductType());
  f.setName(c.getName());f.setNote(c.getNote());f.setVersion(c.getVersion());
  if(c.getSensorCatalog()!=null)f.setSensorCatalogId(c.getSensorCatalog().getId());
  List<ClassifierInclusionForm> rows=new ArrayList<>();
  for(ClassifierInclusion i:c.getInclusions()){
   ClassifierInclusionForm row=new ClassifierInclusionForm();
   row.setTargetId(i.getTarget().getId());row.setQuantity(i.getQuantity());row.setUnit(i.getUnit());rows.add(row);
  }
  f.setInclusions(rows);return f;
 }
    @Transactional(readOnly = true)
    public List<ClassifierOption> inclusionOptions1(
            Long excludedClassifierId
    ) {
        return classifiers
                .findInclusionOptionsDetailed(excludedClassifierId)
                .stream()
                .map(classifier -> new ClassifierOption(
                        classifier.getId(),
                        classifier.getCode(),
                        classifier.getProductType(),
                        classifier.getDisplayName()
                ))
                .toList();
    }
 @Transactional public void create(ClassifierForm f){
  validate(f,null);if(classifiers.existsByCode(f.getCode()))throw new IllegalArgumentException("Код уже используется");
  ProductClassifier c=new ProductClassifier();copy(f,c);replaceRows(f,c);save(c);
 }
 @Transactional public void update(Long id,ClassifierForm f){
  ProductClassifier c=get(id);validate(f,id);
  if(!Objects.equals(c.getVersion(),f.getVersion()))throw new IllegalStateException("Запись уже изменена. Обновите страницу.");
  if(classifiers.existsByCodeAndIdNot(f.getCode(),id))throw new IllegalArgumentException("Код уже используется");
  copy(f,c);c.clearInclusions();classifiers.flush();replaceRows(f,c);save(c);
 }
 private ProductClassifier get(Long id){return classifiers.findDetailedById(id).orElseThrow(()->new EntityNotFoundException("Классификатор не найден"));}
 private void copy(ClassifierForm f,ProductClassifier c){
  c.setCode(f.getCode());c.setProductType(f.getProductType());c.setNote(trim(f.getNote()));
  if(f.getProductType()==ProductType.SENSOR){
   c.setSensorCatalog(sensors.findById(f.getSensorCatalogId()).orElseThrow(()->new EntityNotFoundException("Датчик не найден")));c.setName(null);
  }else{c.setSensorCatalog(null);c.setName(trim(f.getName()));}
 }
 private void replaceRows(ClassifierForm f,ProductClassifier owner){
  List<ClassifierInclusionForm> rows=f.getInclusions()==null?List.of():f.getInclusions();
  Set<Long> ids=new HashSet<>();for(var row:rows)ids.add(row.getTargetId());
  Map<Long,ProductClassifier> targets=new HashMap<>();classifiers.findAllById(ids).forEach(x->targets.put(x.getId(),x));
  if(targets.size()!=ids.size())throw new EntityNotFoundException("Одно из входящих изделий не найдено");
  int pos=0;for(var row:rows){ClassifierInclusion i=new ClassifierInclusion();i.setTarget(targets.get(row.getTargetId()));i.setQuantity(row.getQuantity());i.setUnit(row.getUnit());i.setPosition(pos++);owner.addInclusion(i);}
 }
 private void validate(ClassifierForm f,Long current){
  if(f.getProductType()==ProductType.SENSOR&&f.getSensorCatalogId()==null)throw new IllegalArgumentException("Выберите датчик");
  if(f.getProductType()!=null&&f.getProductType()!=ProductType.SENSOR&&trim(f.getName())==null)throw new IllegalArgumentException("Укажите наименование");
  Set<Long> ids=new HashSet<>();for(var row:f.getInclusions()==null?List.<ClassifierInclusionForm>of():f.getInclusions()){
   if(Objects.equals(current,row.getTargetId()))throw new IllegalArgumentException("Классификатор не может входить сам в себя");
   if(!ids.add(row.getTargetId()))throw new IllegalArgumentException("Входящее изделие повторяется");
  }
 }
 private void save(ProductClassifier c){try{classifiers.saveAndFlush(c);}catch(DataIntegrityViolationException e){throw new IllegalArgumentException("Не удалось сохранить: проверьте уникальность кода и связи",e);}}
 private String trim(String s){if(s==null)return null;s=s.trim();return s.isEmpty()?null:s;}
}
